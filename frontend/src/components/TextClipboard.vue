<script setup>
defineProps({
  modelValue: {
    type: String,
    required: true
  }
});

const emit = defineEmits([
  "update:modelValue",
  "composition-start",
  "composition-end"
]);

function onInput(event) {
  emit("update:modelValue", event.target.value);
}

function onCompositionEnd(event) {
  emit("composition-end", event.target.value);
}
</script>

<template>
  <section class="panel stack text-panel">
    <div class="section-heading">
      <div>
        <p class="eyebrow">Notes</p>
        <h2>文本粘贴板</h2>
      </div>
    </div>

    <textarea
      class="clipboard-textarea"
      :value="modelValue"
      placeholder="输入的内容会实时同步到其它客户端"
      @input="onInput"
      @compositionstart="emit('composition-start')"
      @compositionend="onCompositionEnd"
    />
  </section>
</template>
