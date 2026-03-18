<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import QRCode from "qrcode";
import FileTable from "./components/FileTable.vue";
import UploadPanel from "./components/UploadPanel.vue";
import TextClipboard from "./components/TextClipboard.vue";

const files = ref([]);
const text = ref("");
const workspacePath = ref("");
const isComposing = ref(false);
const qrCodeDataUrl = ref("");
const qrExpanded = ref(false);
const uploadItems = ref([]);
const uploadPanelExpanded = ref(true);
const uploadCleanupTimers = new Map();

function createClientId() {
  if (typeof crypto !== "undefined") {
    if (typeof crypto.randomUUID === "function") {
      return crypto.randomUUID();
    }
    if (typeof crypto.getRandomValues === "function") {
      const bytes = crypto.getRandomValues(new Uint8Array(16));
      bytes[6] = (bytes[6] & 0x0f) | 0x40;
      bytes[8] = (bytes[8] & 0x3f) | 0x80;
      const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0"));
      return [
        hex.slice(0, 4).join(""),
        hex.slice(4, 6).join(""),
        hex.slice(6, 8).join(""),
        hex.slice(8, 10).join(""),
        hex.slice(10, 16).join("")
      ].join("-");
    }
  }

  return `client-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

const clientId = createClientId();
let eventSource;

function formatSpeed(bytesPerSec) {
  if (bytesPerSec < 1024) return `${bytesPerSec.toFixed(1)} B/s`;
  if (bytesPerSec < 1024 * 1024) return `${(bytesPerSec / 1024).toFixed(1)} KB/s`;
  return `${(bytesPerSec / 1024 / 1024).toFixed(2)} MB/s`;
}

function formatFileSize(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

function removeUploadItem(id) {
  const timer = uploadCleanupTimers.get(id);
  if (timer) {
    clearTimeout(timer);
    uploadCleanupTimers.delete(id);
  }
  uploadItems.value = uploadItems.value.filter((item) => item.id !== id);
}

function scheduleUploadCleanup(id) {
  const existingTimer = uploadCleanupTimers.get(id);
  if (existingTimer) {
    clearTimeout(existingTimer);
  }
  const timer = window.setTimeout(() => {
    removeUploadItem(id);
  }, 5000);
  uploadCleanupTimers.set(id, timer);
}

async function requestJson(url, options) {
  const response = await fetch(url, options);
  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || "Request failed");
  }
  const contentType = response.headers.get("content-type") || "";
  return contentType.includes("application/json") ? response.json() : response.text();
}

async function fetchFiles() {
  files.value = await requestJson("/api/file/list");
}

async function fetchText() {
  text.value = await requestJson("/api/notepad/text");
}

async function fetchWorkspace() {
  const workspace = await requestJson("/api/file/workspace");
  workspacePath.value = workspace.path || "";
}

async function saveText(value) {
  text.value = value;
  await requestJson("/api/notepad/text", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      text: value,
      clientId
    })
  });
}

async function deleteFile(fileName) {
  await requestJson("/api/file/delete", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ fileName })
  });
}

async function uploadSingleFile(file) {
  const uploadItem = {
    id: `${Date.now()}-${Math.random().toString(16).slice(2)}`,
    fileName: file.name,
    fileSize: formatFileSize(file.size),
    percent: 0,
    speed: "0 B/s",
    status: "uploading",
    message: "正在上传"
  };
  uploadItems.value = [uploadItem, ...uploadItems.value];

  const startedAt = Date.now();
  const formData = new FormData();
  formData.append("files", file);

  await new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("POST", "/api/file/upload");

    xhr.upload.addEventListener("progress", (event) => {
      if (!event.lengthComputable) {
        return;
      }
      const duration = Math.max((Date.now() - startedAt) / 1000, 0.1);
      Object.assign(uploadItem, {
        percent: Math.round((event.loaded / event.total) * 100),
        speed: formatSpeed(event.loaded / duration),
        status: "uploading",
        message: "正在上传"
      });
      uploadItems.value = [...uploadItems.value];
    });

    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        Object.assign(uploadItem, {
          percent: 100,
          speed: "完成",
          status: "success",
          message: "上传完成"
        });
        uploadItems.value = [...uploadItems.value];
        scheduleUploadCleanup(uploadItem.id);
        resolve();
        return;
      }
      const message = xhr.responseText || "Upload failed";
      Object.assign(uploadItem, {
        status: "error",
        message,
        speed: "失败"
      });
      uploadItems.value = [...uploadItems.value];
      reject(new Error(message));
    };

    xhr.onerror = () => {
      Object.assign(uploadItem, {
        status: "error",
        message: "Upload failed",
        speed: "失败"
      });
      uploadItems.value = [...uploadItems.value];
      reject(new Error("Upload failed"));
    };
    xhr.send(formData);
  });
}

async function uploadFiles(filesToUpload) {
  if (!filesToUpload?.length) {
    return;
  }

  const results = await Promise.allSettled(filesToUpload.map((file) => uploadSingleFile(file)));
  const firstError = results.find((result) => result.status === "rejected");
  if (firstError?.reason) {
    throw firstError.reason;
  }
}

function connectSse() {
  eventSource = new EventSource(`/api/sse/connect?clientId=${clientId}`);
  eventSource.addEventListener("message", async (event) => {
    const message = JSON.parse(event.data);
    if (message.type === "FILE_CHANGED") {
      await fetchFiles();
    }
    if (message.type === "TEXT_CHANGED") {
      text.value = message.content;
    }
  });
}

async function generateQrCode() {
  qrCodeDataUrl.value = await QRCode.toDataURL(window.location.href, {
    width: 160,
    margin: 1,
    color: {
      dark: "#124734",
      light: "#f6f3eb"
    }
  });
}

function onGlobalPaste(event) {
  const clipboardItems = Array.from(event.clipboardData?.items || []);
  const pastedFiles = clipboardItems
    .filter((item) => item.kind === "file")
    .map((item) => item.getAsFile())
    .filter(Boolean);

  if (pastedFiles.length) {
    uploadFiles(pastedFiles).catch((error) => window.alert(error.message));
  }
}

function onCompositionStart() {
  isComposing.value = true;
}

function onCompositionEnd(value) {
  isComposing.value = false;
  saveText(value).catch((error) => window.alert(error.message));
}

function onTextInput(value) {
  if (isComposing.value) {
    text.value = value;
    return;
  }
  saveText(value).catch((error) => window.alert(error.message));
}

onMounted(async () => {
  document.addEventListener("paste", onGlobalPaste);
  await Promise.all([
    fetchFiles(),
    fetchText(),
    generateQrCode(),
    fetchWorkspace().catch(() => {
      workspacePath.value = "工作目录获取失败";
    })
  ]);
  connectSse();
});

onBeforeUnmount(() => {
  document.removeEventListener("paste", onGlobalPaste);
  eventSource?.close();
  uploadCleanupTimers.forEach((timer) => clearTimeout(timer));
  uploadCleanupTimers.clear();
});
</script>

<template>
  <div class="page-shell">
    <header class="hero">
      <div class="hero-main">
        <p class="eyebrow">LAN Workspace</p>
        <h1>ShareIt</h1>
        <p v-if="workspacePath" class="workspace-path" :title="workspacePath">
          工作目录：{{ workspacePath }}
        </p>
      </div>
      <button
        type="button"
        class="qr-mini"
        aria-label="Open QR code"
        @click="qrExpanded = true"
      >
        <img v-if="qrCodeDataUrl" :src="qrCodeDataUrl" alt="ShareIt QR Code" />
      </button>
    </header>

    <Transition name="qr-overlay">
      <div
        v-if="qrExpanded"
        class="qr-overlay"
        @click.self="qrExpanded = false"
      >
        <button
          type="button"
          class="qr-overlay-close"
          aria-label="Close QR code"
          @click="qrExpanded = false"
        >
          ×
        </button>
        <div class="qr-modal">
          <img v-if="qrCodeDataUrl" :src="qrCodeDataUrl" alt="ShareIt QR Code" />
          <p>扫码访问当前页面</p>
        </div>
      </div>
    </Transition>

    <main class="content-grid">
      <TextClipboard
        :model-value="text"
        @update:model-value="onTextInput"
        @composition-start="onCompositionStart"
        @composition-end="onCompositionEnd"
      />

      <section class="panel stack">
        <UploadPanel @upload="uploadFiles" />
        <FileTable :files="files" @delete="deleteFile" />
      </section>
    </main>

    <aside
      v-if="uploadItems.length"
      class="upload-float"
      :class="{ collapsed: !uploadPanelExpanded }"
    >
      <button
        type="button"
        class="upload-float-toggle"
        @click="uploadPanelExpanded = !uploadPanelExpanded"
      >
        <span>上传队列</span>
        <strong>{{ uploadItems.length }}</strong>
      </button>

      <Transition name="upload-float-body">
        <div v-if="uploadPanelExpanded" class="upload-float-body">
          <div class="upload-list">
            <article
              v-for="item in uploadItems"
              :key="item.id"
              class="upload-item"
              :class="`is-${item.status}`"
            >
              <div class="progress-shell">
                <div class="progress-bar" :style="{ width: `${item.percent}%` }"></div>
                <span>{{ item.fileName }} · {{ item.percent }}% · {{ item.speed }}</span>
              </div>
              <p class="upload-status">{{ item.fileSize }} · {{ item.message }}</p>
            </article>
          </div>
        </div>
      </Transition>
    </aside>
  </div>
</template>
