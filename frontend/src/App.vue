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
const downloadItems = ref([]);
const transferPanelExpanded = ref(true);
const taskCleanupTimers = new Map();
const downloadRuntimes = new Map();

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

function createTaskId(prefix) {
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

const clientId = createClientId();
let eventSource;

const transferItems = computed(() =>
  [...downloadItems.value, ...uploadItems.value].sort((left, right) => right.createdAt - left.createdAt)
);
const uploadCount = computed(() => uploadItems.value.length);
const downloadCount = computed(() => downloadItems.value.length);

function formatSpeed(bytesPerSec) {
  if (bytesPerSec < 1024) return `${bytesPerSec.toFixed(1)} B/s`;
  if (bytesPerSec < 1024 * 1024) return `${(bytesPerSec / 1024).toFixed(1)} KB/s`;
  return `${(bytesPerSec / 1024 / 1024).toFixed(2)} MB/s`;
}

function formatFileSize(bytes) {
  if (!Number.isFinite(bytes) || bytes < 0) {
    return "未知大小";
  }
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

function clearTaskCleanup(id) {
  const timer = taskCleanupTimers.get(id);
  if (timer) {
    clearTimeout(timer);
    taskCleanupTimers.delete(id);
  }
}

function scheduleTaskCleanup(id, removeTask) {
  clearTaskCleanup(id);
  const timer = window.setTimeout(() => {
    removeTask(id);
  }, 5000);
  taskCleanupTimers.set(id, timer);
}

function refreshUploadItems() {
  uploadItems.value = [...uploadItems.value];
}

function refreshDownloadItems() {
  downloadItems.value = [...downloadItems.value];
}

function removeUploadItem(id) {
  clearTaskCleanup(id);
  uploadItems.value = uploadItems.value.filter((item) => item.id !== id);
}

function cleanupDownloadRuntime(id) {
  const runtime = downloadRuntimes.get(id);
  if (!runtime) {
    return;
  }
  runtime.controller?.abort();
  if (runtime.objectUrl) {
    URL.revokeObjectURL(runtime.objectUrl);
  }
  downloadRuntimes.delete(id);
}

function removeDownloadItem(id) {
  clearTaskCleanup(id);
  cleanupDownloadRuntime(id);
  downloadItems.value = downloadItems.value.filter((item) => item.id !== id);
}

function requestRuntime(id) {
  if (!downloadRuntimes.has(id)) {
    downloadRuntimes.set(id, {
      controller: null,
      chunks: [],
      objectUrl: "",
      abortReason: ""
    });
  }
  return downloadRuntimes.get(id);
}

function parseContentRange(contentRange) {
  const match = /^bytes\s+(\d+)-(\d+)\/(\d+)$/.exec(contentRange || "");
  if (!match) {
    return null;
  }
  return {
    start: Number(match[1]),
    end: Number(match[2]),
    total: Number(match[3])
  };
}

function extractFileName(contentDisposition, fallbackName) {
  const utf8Match = /filename\*=UTF-8''([^;]+)/i.exec(contentDisposition || "");
  if (utf8Match?.[1]) {
    try {
      return decodeURIComponent(utf8Match[1]);
    } catch {
      return fallbackName;
    }
  }
  const basicMatch = /filename="([^"]+)"/i.exec(contentDisposition || "");
  return basicMatch?.[1] || fallbackName;
}

function saveBlob(blob, fileName, id) {
  const runtime = requestRuntime(id);
  if (runtime.objectUrl) {
    URL.revokeObjectURL(runtime.objectUrl);
  }
  runtime.objectUrl = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = runtime.objectUrl;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.setTimeout(() => {
    const currentRuntime = downloadRuntimes.get(id);
    if (currentRuntime?.objectUrl) {
      URL.revokeObjectURL(currentRuntime.objectUrl);
      currentRuntime.objectUrl = "";
    }
  }, 1000);
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
    id: createTaskId("upload"),
    kind: "upload",
    createdAt: Date.now(),
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
      refreshUploadItems();
    });

    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        Object.assign(uploadItem, {
          percent: 100,
          speed: "完成",
          status: "success",
          message: "上传完成"
        });
        refreshUploadItems();
        scheduleTaskCleanup(uploadItem.id, removeUploadItem);
        resolve();
        return;
      }
      const message = xhr.responseText || "Upload failed";
      Object.assign(uploadItem, {
        status: "error",
        message,
        speed: "失败"
      });
      refreshUploadItems();
      reject(new Error(message));
    };

    xhr.onerror = () => {
      Object.assign(uploadItem, {
        status: "error",
        message: "Upload failed",
        speed: "失败"
      });
      refreshUploadItems();
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

function updateDownloadProgress(item, loadedBytes, totalBytes, runtime) {
  item.downloadedBytes = loadedBytes;
  item.totalBytes = totalBytes;
  item.fileSize = formatFileSize(totalBytes);
  item.percent = totalBytes > 0 ? Math.min(100, Math.round((loadedBytes / totalBytes) * 100)) : 0;

  const now = Date.now();
  const elapsed = Math.max((now - runtime.lastSampleAt) / 1000, 0.1);
  const deltaBytes = loadedBytes - runtime.lastSampleBytes;
  item.speed = formatSpeed(Math.max(deltaBytes, 0) / elapsed);
  item.message = totalBytes > 0
    ? `${formatFileSize(loadedBytes)} / ${formatFileSize(totalBytes)}`
    : `已下载 ${formatFileSize(loadedBytes)}`;

  runtime.lastSampleAt = now;
  runtime.lastSampleBytes = loadedBytes;
  refreshDownloadItems();
}

async function runDownload(item) {
  const runtime = requestRuntime(item.id);
  const startOffset = item.downloadedBytes;
  const headers = {};
  if (startOffset > 0) {
    headers.Range = `bytes=${startOffset}-`;
    if (item.eTag) {
      headers["If-Range"] = item.eTag;
    } else if (item.lastModified) {
      headers["If-Range"] = item.lastModified;
    }
  }

  runtime.abortReason = "";
  runtime.controller = new AbortController();
  runtime.lastSampleAt = Date.now();
  runtime.lastSampleBytes = item.downloadedBytes;

  item.status = "downloading";
  item.speed = "0 B/s";
  item.message = startOffset > 0 ? "继续下载中" : "正在下载";
  refreshDownloadItems();

  try {
    const response = await fetch(item.fileUrl, {
      headers,
      signal: runtime.controller.signal
    });

    if (!response.ok) {
      const message = await response.text();
      throw new Error(message || "Download failed");
    }

    const contentDisposition = response.headers.get("content-disposition") || "";
    item.fileName = extractFileName(contentDisposition, item.fileName);
    item.eTag = response.headers.get("etag") || item.eTag;
    item.lastModified = response.headers.get("last-modified") || item.lastModified;

    let totalBytes = item.totalBytes;
    if (response.status === 206) {
      const contentRange = parseContentRange(response.headers.get("content-range") || "");
      if (contentRange?.total) {
        totalBytes = contentRange.total;
      }
    } else {
      const fullLength = Number(response.headers.get("content-length") || 0);
      if (fullLength > 0) {
        totalBytes = fullLength;
      }
      if (startOffset > 0) {
        runtime.chunks = [];
        item.downloadedBytes = 0;
        item.percent = 0;
      }
    }

    if (!totalBytes) {
      const fallbackLength = Number(response.headers.get("content-length") || 0);
      totalBytes = response.status === 206 ? startOffset + fallbackLength : fallbackLength;
    }

    const reader = response.body?.getReader();
    if (!reader) {
      throw new Error("当前浏览器不支持流式下载");
    }

    let loadedBytes = item.downloadedBytes;
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      runtime.chunks.push(value);
      loadedBytes += value.byteLength;
      updateDownloadProgress(item, loadedBytes, totalBytes, runtime);
    }

    const blob = new Blob(runtime.chunks, {
      type: response.headers.get("content-type") || "application/octet-stream"
    });
    saveBlob(blob, item.fileName, item.id);
    runtime.chunks = [];
    runtime.controller = null;

    Object.assign(item, {
      totalBytes,
      downloadedBytes: totalBytes,
      percent: 100,
      speed: "完成",
      status: "success",
      message: "下载完成"
    });
    refreshDownloadItems();
    scheduleTaskCleanup(item.id, removeDownloadItem);
  } catch (error) {
    runtime.controller = null;
    if (error?.name === "AbortError") {
      if (runtime.abortReason === "pause") {
        item.status = "paused";
        item.speed = "已暂停";
        item.message = `已暂停，可从 ${formatFileSize(item.downloadedBytes)} 继续`;
        refreshDownloadItems();
        return;
      }
      if (runtime.abortReason === "cancel") {
        removeDownloadItem(item.id);
        return;
      }
    }

    item.status = "paused";
    item.speed = "已中断";
    item.message = `${error.message || "下载中断"}，可继续`;
    refreshDownloadItems();
  }
}

function startDownload(file) {
  const existing = downloadItems.value.find(
    (item) => item.fileUrl === file.fileUrl && !["success"].includes(item.status)
  );
  if (existing) {
    if (existing.status !== "downloading") {
      runDownload(existing).catch((error) => {
        existing.status = "paused";
        existing.message = `${error.message || "下载中断"}，可继续`;
        refreshDownloadItems();
      });
    }
    return;
  }

  const downloadItem = {
    id: createTaskId("download"),
    kind: "download",
    createdAt: Date.now(),
    fileName: file.fileName,
    fileUrl: file.fileUrl,
    fileSize: file.fileSize || "未知大小",
    percent: 0,
    speed: "0 B/s",
    status: "pending",
    message: "等待下载",
    downloadedBytes: 0,
    totalBytes: 0,
    eTag: "",
    lastModified: ""
  };
  downloadItems.value = [downloadItem, ...downloadItems.value];

  runDownload(downloadItem).catch((error) => {
    downloadItem.status = "paused";
    downloadItem.message = `${error.message || "下载中断"}，可继续`;
    refreshDownloadItems();
  });
}

function pauseDownload(id) {
  const item = downloadItems.value.find((current) => current.id === id);
  const runtime = downloadRuntimes.get(id);
  if (!item || item.status !== "downloading" || !runtime?.controller) {
    return;
  }
  runtime.abortReason = "pause";
  runtime.controller.abort();
}

function resumeDownload(id) {
  const item = downloadItems.value.find((current) => current.id === id);
  if (!item || item.status === "downloading" || item.status === "success") {
    return;
  }
  clearTaskCleanup(id);
  runDownload(item).catch((error) => {
    item.status = "paused";
    item.message = `${error.message || "下载中断"}，可继续`;
    refreshDownloadItems();
  });
}

function cancelDownload(id) {
  clearTaskCleanup(id);
  const runtime = downloadRuntimes.get(id);
  if (runtime?.controller) {
    runtime.abortReason = "cancel";
    runtime.controller.abort();
    return;
  }
  removeDownloadItem(id);
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
  taskCleanupTimers.forEach((timer) => clearTimeout(timer));
  taskCleanupTimers.clear();
  Array.from(downloadRuntimes.entries()).forEach(([id, runtime]) => {
    runtime.abortReason = "cancel";
    runtime.controller?.abort();
    if (runtime.objectUrl) {
      URL.revokeObjectURL(runtime.objectUrl);
    }
    downloadRuntimes.delete(id);
  });
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
        <FileTable :files="files" @delete="deleteFile" @download="startDownload" />
      </section>
    </main>

    <aside
      v-if="transferItems.length"
      class="transfer-float"
      :class="{ collapsed: !transferPanelExpanded }"
    >
      <button
        type="button"
        class="transfer-float-toggle"
        @click="transferPanelExpanded = !transferPanelExpanded"
      >
        <span>传输队列</span>
        <div class="transfer-counts">
          <em v-if="downloadCount">下 {{ downloadCount }}</em>
          <em v-if="uploadCount">上 {{ uploadCount }}</em>
          <strong>{{ transferItems.length }}</strong>
        </div>
      </button>

      <Transition name="transfer-float-body">
        <div v-if="transferPanelExpanded" class="transfer-float-body">
          <div class="transfer-list">
            <article
              v-for="item in transferItems"
              :key="item.id"
              class="transfer-item"
              :class="[item.kind, `is-${item.status}`]"
            >
              <div class="transfer-item-head">
                <span class="transfer-tag">
                  {{ item.kind === "download" ? "下载" : "上传" }}
                </span>
                <strong>{{ item.fileName }}</strong>
              </div>

              <div class="progress-shell" :class="item.kind">
                <div class="progress-bar" :style="{ width: `${item.percent}%` }"></div>
                <span>{{ item.percent }}% · {{ item.speed }}</span>
              </div>

              <p class="transfer-status">{{ item.fileSize }} · {{ item.message }}</p>

              <div v-if="item.kind === 'download'" class="transfer-actions">
                <button
                  v-if="item.status === 'downloading'"
                  type="button"
                  class="tiny-action subtle"
                  @click="pauseDownload(item.id)"
                >
                  暂停
                </button>
                <button
                  v-if="['paused', 'pending'].includes(item.status)"
                  type="button"
                  class="tiny-action primary"
                  @click="resumeDownload(item.id)"
                >
                  继续
                </button>
                <button
                  v-if="item.status !== 'success'"
                  type="button"
                  class="tiny-action danger"
                  @click="cancelDownload(item.id)"
                >
                  取消
                </button>
                <button
                  v-if="item.status === 'success'"
                  type="button"
                  class="tiny-action subtle"
                  @click="removeDownloadItem(item.id)"
                >
                  关闭
                </button>
              </div>
            </article>
          </div>
        </div>
      </Transition>
    </aside>
  </div>
</template>
