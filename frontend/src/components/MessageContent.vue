<script setup>
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

const props = defineProps({ content: { type: String, required: true } })
const html = computed(() => DOMPurify.sanitize(marked.parse(props.content, { breaks: true }), {
  USE_PROFILES: { html: true },
  FORBID_TAGS: ['img', 'input', 'form', 'button', 'style'],
  FORBID_ATTR: ['style'],
}))
</script>

<template>
  <div class="markdown" v-html="html"></div>
</template>
