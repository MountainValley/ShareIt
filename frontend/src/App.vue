<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import QRCode from "qrcode";
import FileTable from "./components/FileTable.vue";
import UploadPanel from "./components/UploadPanel.vue";
import TextClipboard from "./components/TextClipboard.vue";

const LARGE_FILE_THRESHOLD = 8 * 1024 * 1024;
const UPLOAD_CHUNK_SIZE = 2 * 1024 * 1024;
const UPLOAD_PARALLEL_LIMIT = 3;
const DOWNLOAD_PART_SIZE = 4 * 1024 * 1024;
const DOWNLOAD_PARALLEL_LIMIT = 4;

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
  runtime.controllers.forEach((controller) => controller.abort());
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
      controllers: [],
      objectUrl: "",
      abortReason: "",
      strategy: "single",
      parts: [],
      probeLoaded: false,
      transferStartedAt: 0,
      lastSampleAt: 0,
      lastSampleBytes: 0,
      lastRenderedSpeed: "0 B/s"
    });
  }
  return downloadRuntimes.get(id);
}

function resetRuntimeControllers(runtime) {
  runtime.controllers = [];
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

function updateUploadProgress(item, loadedBytes, startedAt, message) {
  const duration = Math.max((Date.now() - startedAt) / 1000, 0.1);
  Object.assign(item, {
    percent: Math.min(100, Math.round((loadedBytes / item.totalBytes) * 100)),
    speed: formatSpeed(loadedBytes / duration),
    status: "uploading",
    message
  });
  refreshUploadItems();
}

async function uploadSingleFile(file) {
  const chunked = file.size >= LARGE_FILE_THRESHOLD;
  const uploadItem = {
    id: createTaskId("upload"),
    kind: "upload",
    createdAt: Date.now(),
    fileName: file.name,
    fileSize: formatFileSize(file.size),
    totalBytes: file.size,
    percent: 0,
    speed: "0 B/s",
    status: "uploading",
    message: chunked ? "准备分片上传" : "正在上传",
    accelerated: chunked
  };
  uploadItems.value = [uploadItem, ...uploadItems.value];

  if (chunked) {
    await uploadLargeFile(file, uploadItem);
  } else {
    await uploadRegularFile(file, uploadItem);
  }
}

async function uploadRegularFile(file, uploadItem) {
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
      updateUploadProgress(uploadItem, event.loaded, startedAt, "正在上传");
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

async function uploadLargeFile(file, uploadItem) {
  const uploadId = createTaskId("chunk");
  const totalChunks = Math.ceil(file.size / UPLOAD_CHUNK_SIZE);
  const startedAt = Date.now();
  const chunkProgress = Array.from({ length: totalChunks }, () => 0);
  let completedChunks = 0;
  let nextChunkIndex = 0;

  function refreshChunkUploadProgress() {
    const uploadedBytes = chunkProgress.reduce((sum, value) => sum + value, 0);
    updateUploadProgress(
      uploadItem,
      uploadedBytes,
      startedAt,
      `分片并发上传 ${completedChunks}/${totalChunks}`
    );
  }

  async function uploadChunkAt(chunkIndex) {
    const start = chunkIndex * UPLOAD_CHUNK_SIZE;
    const end = Math.min(file.size, start + UPLOAD_CHUNK_SIZE);
    const chunk = file.slice(start, end);

    await new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      const formData = new FormData();
      formData.append("uploadId", uploadId);
      formData.append("fileName", file.name);
      formData.append("chunkIndex", String(chunkIndex));
      formData.append("totalChunks", String(totalChunks));
      formData.append("totalSize", String(file.size));
      formData.append("chunk", chunk, `${file.name}.part-${chunkIndex}`);
      xhr.open("POST", "/api/file/upload/chunk");

      xhr.upload.addEventListener("progress", (event) => {
        if (!event.lengthComputable) {
          return;
        }
        chunkProgress[chunkIndex] = event.loaded;
        refreshChunkUploadProgress();
      });

      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          chunkProgress[chunkIndex] = chunk.size;
          completedChunks += 1;
          refreshChunkUploadProgress();
          resolve();
          return;
        }
        reject(new Error(xhr.responseText || "Chunk upload failed"));
      };

      xhr.onerror = () => reject(new Error("Chunk upload failed"));
      xhr.send(formData);
    });
  }

  async function chunkWorker() {
    while (nextChunkIndex < totalChunks) {
      const currentIndex = nextChunkIndex;
      nextChunkIndex += 1;
      await uploadChunkAt(currentIndex);
    }
  }

  await Promise.all(
    Array.from({ length: Math.min(UPLOAD_PARALLEL_LIMIT, totalChunks) }, () => chunkWorker())
  );

  await requestJson("/api/file/upload/complete", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      uploadId,
      fileName: file.name,
      totalChunks,
      totalSize: file.size
    })
  });

  Object.assign(uploadItem, {
    percent: 100,
    speed: "完成",
    status: "success",
    message: "分片并发上传完成"
  });
  refreshUploadItems();
  scheduleTaskCleanup(uploadItem.id, removeUploadItem);
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

function updateDownloadProgress(item, loadedBytes, runtime) {
  item.downloadedBytes = loadedBytes;
  item.fileSize = formatFileSize(item.totalBytes);
  item.percent = item.totalBytes > 0 ? Math.min(100, Math.round((loadedBytes / item.totalBytes) * 100)) : 0;

  const now = Date.now();
  const elapsedSinceSample = now - runtime.lastSampleAt;
  if (elapsedSinceSample >= 250 || loadedBytes === item.totalBytes) {
    const elapsed = Math.max(elapsedSinceSample / 1000, 0.1);
    const deltaBytes = loadedBytes - runtime.lastSampleBytes;
    runtime.lastRenderedSpeed = formatSpeed(Math.max(deltaBytes, 0) / elapsed);
    runtime.lastSampleAt = now;
    runtime.lastSampleBytes = loadedBytes;
  }
  item.speed = runtime.lastRenderedSpeed;
  item.message = item.totalBytes > 0
    ? `${formatFileSize(loadedBytes)} / ${formatFileSize(item.totalBytes)}`
    : `已下载 ${formatFileSize(loadedBytes)}`;
  refreshDownloadItems();
}

function createDownloadParts(totalBytes) {
  const parts = [];
  for (let start = 0; start < totalBytes; start += DOWNLOAD_PART_SIZE) {
    const end = Math.min(totalBytes - 1, start + DOWNLOAD_PART_SIZE - 1);
    parts.push({
      start,
      end,
      loaded: 0,
      chunks: []
    });
  }
  return parts;
}

function getDownloadedBytes(runtime) {
  return runtime.parts.reduce((sum, part) => sum + part.loaded, 0);
}

async function probeDownload(item) {
  if (item.totalBytes > 0 && item.eTag) {
    return;
  }

  const runtime = requestRuntime(item.id);
  const controller = new AbortController();
  runtime.controllers.push(controller);
  const response = await fetch(item.fileUrl, {
    headers: { Range: "bytes=0-0" },
    signal: controller.signal
  });
  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || "Download probe failed");
  }

  item.fileName = extractFileName(response.headers.get("content-disposition") || "", item.fileName);
  item.eTag = response.headers.get("etag") || item.eTag;
  item.lastModified = response.headers.get("last-modified") || item.lastModified;

  if (response.status === 206) {
    const contentRange = parseContentRange(response.headers.get("content-range") || "");
    if (contentRange?.total) {
      item.totalBytes = contentRange.total;
    }
  } else {
    item.totalBytes = Number(response.headers.get("content-length") || 0);
  }

  if (response.body) {
    const reader = response.body.getReader();
    while (!(await reader.read()).done) {
      // Drain the probe response to free the connection.
    }
  }
}

async function runSingleDownload(item, runtime) {
  const controller = new AbortController();
  runtime.controllers.push(controller);
  const headers = {};
  if (item.downloadedBytes > 0) {
    headers.Range = `bytes=${item.downloadedBytes}-`;
    if (item.eTag) {
      headers["If-Range"] = item.eTag;
    } else if (item.lastModified) {
      headers["If-Range"] = item.lastModified;
    }
  }

  const response = await fetch(item.fileUrl, {
    headers,
    signal: controller.signal
  });
  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || "Download failed");
  }

  const contentDisposition = response.headers.get("content-disposition") || "";
  item.fileName = extractFileName(contentDisposition, item.fileName);
  const reader = response.body?.getReader();
  if (!reader) {
    throw new Error("当前浏览器不支持流式下载");
  }

  const chunks = runtime.parts[0].chunks;
  let loadedBytes = item.downloadedBytes;
  while (true) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    chunks.push(value);
    loadedBytes += value.byteLength;
    runtime.parts[0].loaded = loadedBytes;
    updateDownloadProgress(item, loadedBytes, runtime);
  }

  return new Blob(chunks, {
    type: response.headers.get("content-type") || "application/octet-stream"
  });
}

async function downloadPart(item, part, runtime) {
  if (part.loaded > part.end - part.start) {
    return;
  }
  const rangeStart = part.start + part.loaded;
  const controller = new AbortController();
  runtime.controllers.push(controller);

  const headers = {
    Range: `bytes=${rangeStart}-${part.end}`
  };
  if (item.eTag) {
    headers["If-Range"] = item.eTag;
  } else if (item.lastModified) {
    headers["If-Range"] = item.lastModified;
  }

  const response = await fetch(item.fileUrl, {
    headers,
    signal: controller.signal
  });
  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || "Download failed");
  }
  if (response.status !== 206) {
    throw new Error("服务器未返回分段数据，无法并发下载");
  }

  const reader = response.body?.getReader();
  if (!reader) {
    throw new Error("当前浏览器不支持流式下载");
  }

  while (true) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    part.chunks.push(value);
    part.loaded += value.byteLength;
    updateDownloadProgress(item, getDownloadedBytes(runtime), runtime);
  }
}

async function runParallelDownload(item, runtime) {
  const pendingParts = runtime.parts.filter((part) => part.loaded <= part.end - part.start);
  for (let index = 0; index < pendingParts.length; index += DOWNLOAD_PARALLEL_LIMIT) {
    const batch = pendingParts.slice(index, index + DOWNLOAD_PARALLEL_LIMIT);
    await Promise.all(batch.map((part) => downloadPart(item, part, runtime)));
  }

  return new Blob(
    runtime.parts.flatMap((part) => part.chunks),
    { type: "application/octet-stream" }
  );
}

async function runDownload(item) {
  const runtime = requestRuntime(item.id);
  runtime.abortReason = "";
  resetRuntimeControllers(runtime);
  runtime.transferStartedAt = Date.now();
  runtime.lastSampleAt = Date.now();
  runtime.lastSampleBytes = item.downloadedBytes;
  runtime.lastRenderedSpeed = "0 B/s";

  item.status = "downloading";
  item.speed = "0 B/s";
  item.message = item.downloadedBytes > 0 ? "继续下载中" : "正在下载";
  refreshDownloadItems();

  try {
    await probeDownload(item);

    if (!runtime.probeLoaded) {
      runtime.strategy = item.totalBytes >= LARGE_FILE_THRESHOLD ? "parallel" : "single";
      runtime.parts = runtime.strategy === "parallel"
        ? createDownloadParts(item.totalBytes)
        : [{ start: 0, end: Math.max(item.totalBytes - 1, 0), loaded: item.downloadedBytes, chunks: [] }];
      runtime.probeLoaded = true;
      item.accelerated = runtime.strategy === "parallel";
    }

    let blob;
    if (runtime.strategy === "parallel") {
      blob = await runParallelDownload(item, runtime);
    } else {
      blob = await runSingleDownload(item, runtime);
    }

    saveBlob(blob, item.fileName, item.id);
    resetRuntimeControllers(runtime);

    Object.assign(item, {
      downloadedBytes: item.totalBytes,
      percent: 100,
      speed: "完成",
      status: "success",
      message: runtime.strategy === "parallel" ? "并发下载完成" : "下载完成"
    });
    refreshDownloadItems();
    scheduleTaskCleanup(item.id, removeDownloadItem);
  } catch (error) {
    resetRuntimeControllers(runtime);
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
    (item) => item.fileUrl === file.fileUrl && item.status !== "success"
  );
  if (existing) {
    if (existing.status !== "downloading") {
      runDownload(existing).catch(() => {});
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
    lastModified: "",
    accelerated: false
  };
  downloadItems.value = [downloadItem, ...downloadItems.value];

  runDownload(downloadItem).catch(() => {});
}

function pauseDownload(id) {
  const item = downloadItems.value.find((current) => current.id === id);
  const runtime = downloadRuntimes.get(id);
  if (!item || item.status !== "downloading" || !runtime) {
    return;
  }
  runtime.abortReason = "pause";
  runtime.controllers.forEach((controller) => controller.abort());
}

function resumeDownload(id) {
  const item = downloadItems.value.find((current) => current.id === id);
  if (!item || item.status === "downloading" || item.status === "success") {
    return;
  }
  clearTaskCleanup(id);
  runDownload(item).catch(() => {});
}

function cancelDownload(id) {
  clearTaskCleanup(id);
  const runtime = downloadRuntimes.get(id);
  if (runtime) {
    runtime.abortReason = "cancel";
    runtime.controllers.forEach((controller) => controller.abort());
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
    runtime.controllers.forEach((controller) => controller.abort());
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
          <em v-if="downloadCount" class="download">下载 {{ downloadCount }}</em>
          <em v-if="uploadCount" class="upload">上传 {{ uploadCount }}</em>
          <strong>总 {{ transferItems.length }}</strong>
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
                <span v-if="item.accelerated" class="transfer-bolt" title="大文件加速">⚡</span>
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
