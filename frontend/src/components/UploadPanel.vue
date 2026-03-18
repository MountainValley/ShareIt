<script setup>
const emit = defineEmits(["upload"]);

function onChange(event) {
  emit("upload", Array.from(event.target.files || []));
  event.target.value = "";
}

function onDrop(event) {
  event.preventDefault();
  emit("upload", Array.from(event.dataTransfer?.files || []));
}
</script>

<template>
  <section class="panel stack">
    <div class="section-heading">
      <div>
        <p class="eyebrow">Upload</p>
        <h2>上传面板</h2>
      </div>
    </div>

    <label class="upload-zone" @dragover.prevent @drop="onDrop">
      <input type="file" multiple @change="onChange" />
      <span>点击选择文件，或直接拖拽到这里</span>
    </label>
  </section>
</template>
