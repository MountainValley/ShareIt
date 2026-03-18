<script setup>
defineProps({
  files: {
    type: Array,
    required: true
  }
});

defineEmits(["delete"]);
</script>

<template>
  <section class="panel stack">
    <div class="section-heading">
      <div>
        <p class="eyebrow">Files</p>
        <h2>文件中转站</h2>
      </div>
    </div>

    <div class="table-wrap">
      <table class="file-table">
        <thead>
          <tr>
            <th>#</th>
            <th>文件名</th>
            <th>大小</th>
            <th>最后修改</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!files.length">
            <td colspan="5" class="empty-row">暂无文件</td>
          </tr>
          <tr v-for="(file, index) in files" :key="file.filePath">
            <td>{{ index + 1 }}</td>
            <td class="filename">{{ file.fileName }}</td>
            <td>{{ file.fileSize }}</td>
            <td>{{ file.lastModifiedTime }}</td>
            <td class="actions">
              <a :href="file.fileUrl" class="action-link primary">下载</a>
              <button type="button" class="action-link danger" @click="$emit('delete', file.fileName)">
                删除
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
