<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  routes: { type: Array, default: () => [] },
})

const selected = ref(0)
watch(() => props.routes, () => { selected.value = 0 })

const plan = computed(() => props.routes[selected.value] || null)
const rideLines = computed(() => {
  const names = []
  for (const leg of plan.value?.legs || []) {
    if (leg.mode === 'ride' && leg.line && !names.includes(leg.line)) names.push(leg.line)
  }
  return names
})

// 对照表里没有的线路用同一种中性色，避免编一个看起来像官方色的颜色。
const LINE_COLORS = [
  [/屯马/, '#9a4e2c'],
  [/东铁/, '#5eb6e4'],
  [/观塘/, '#1a9431'],
  [/荃湾/, '#e2231a'],
  [/港岛/, '#007dc5'],
  [/将军澳/, '#6b208b'],
  [/东涌|机场/, '#1c7670'],
  [/1\s*号线|一号线/, '#00a650'],
  [/2\s*号线|二号线/, '#c23a30'],
  [/3\s*号线|三号线/, '#00a9e0'],
  [/4\s*号线|四号线/, '#dc4f9a'],
  [/5\s*号线|五号线/, '#9b26b6'],
  [/6\s*号线|六号线/, '#3db5a8'],
  [/7\s*号线|七号线/, '#003da5'],
  [/9\s*号线|九号线/, '#8e6bbf'],
  [/11\s*号线|十一号线/, '#6b2d5b'],
  [/12\s*号线|十二号线/, '#a67c52'],
  [/14\s*号线|十四号线/, '#d4a017'],
  [/16\s*号线|十六号线/, '#2e6b4f'],
  [/20\s*号线|二十号线/, '#c46b2c'],
]

function lineColor(name) {
  for (const [pattern, color] of LINE_COLORS) {
    if (pattern.test(name || '')) return color
  }
  return '#5f7f72'
}

function place(name, exit) {
  if (!name) return '未知站点'
  return exit ? `${name}（${exit}）` : name
}
</script>

<template>
  <section v-if="plan" class="route-card" aria-label="路线图">
    <div v-if="routes.length > 1" class="route-tabs" role="tablist">
      <button
        v-for="(item, index) in routes"
        :key="index"
        type="button"
        role="tab"
        :aria-selected="index === selected"
        :class="{ selected: index === selected }"
        @click="selected = index"
      >
        方案 {{ index + 1 }}
        <small>{{ item.durationMinutes }} 分钟</small>
      </button>
    </div>
    <header class="route-summary">
      <strong>约 {{ plan.durationMinutes }} 分钟</strong>
      <span>步行 {{ plan.walkingMeters }} 米</span>
      <span v-for="line in rideLines" :key="line" class="route-pill" :style="{ background: lineColor(line) }">{{ line }}</span>
    </header>
    <ol class="route-timeline">
      <li v-for="(leg, index) in plan.legs" :key="index" :class="leg.mode === 'ride' ? 'is-ride' : 'is-walk'">
        <template v-if="leg.mode === 'walk'">
          <span class="route-marker walk"></span>
          <p>步行 {{ leg.distanceMeters }} 米 · 约 {{ leg.durationMinutes }} 分钟</p>
        </template>
        <template v-else>
          <span class="route-marker ride" :style="{ background: lineColor(leg.line) }"></span>
          <div class="ride-body" :style="{ borderColor: lineColor(leg.line) }">
            <p class="ride-board"><strong>{{ place(leg.board, leg.boardExit) }}</strong> 上车</p>
            <p class="ride-line">
              {{ leg.line || '未知线路' }}
              <span v-if="leg.direction">往{{ leg.direction }}</span>
              <span v-if="leg.price">· {{ leg.price }}</span>
            </p>
            <ul v-if="leg.stops?.length" class="ride-stops">
              <li v-for="stop in leg.stops" :key="stop">{{ stop }}</li>
            </ul>
            <p v-else class="ride-stops-missing">途经站名未返回</p>
            <p class="ride-alight"><strong>{{ place(leg.alight, leg.alightExit) }}</strong> 下车</p>
            <p v-if="leg.notice" class="ride-notice">{{ leg.notice }}</p>
          </div>
        </template>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.route-card { margin-top: 14px; border: 1px solid #e0e9e2; border-radius: 14px; background: #f7faf8; padding: 12px 14px 8px; }
.route-tabs { display: flex; gap: 8px; margin-bottom: 10px; }
.route-tabs button { border: 1px solid #d5e3da; background: #fff; border-radius: 999px; padding: 6px 10px; color: #4d6458; font-size: 12px; }
.route-tabs button.selected { background: var(--green); border-color: var(--green); color: #fff; }
.route-tabs small { margin-left: 4px; opacity: .8; }
.route-summary { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-bottom: 12px; font-size: 13px; color: #3f5447; }
.route-pill { color: #fff; border-radius: 999px; padding: 2px 8px; font-size: 12px; }
.route-timeline { list-style: none; margin: 0; padding: 0 0 4px; }
.route-timeline li { position: relative; padding: 0 0 12px 22px; }
.route-timeline li::before { content: ""; position: absolute; left: 5px; top: 8px; bottom: -2px; width: 2px; background: #d5e3da; }
.route-timeline li:last-child::before { display: none; }
.route-marker { position: absolute; left: 0; top: 4px; width: 12px; height: 12px; border-radius: 50%; background: #fff; border: 2px solid #8fbfab; }
.route-marker.ride { border: 0; }
.route-timeline p { margin: 0; font-size: 13px; line-height: 1.5; color: #3f5447; }
.ride-body { border-left: 3px solid #5f7f72; padding-left: 10px; }
.ride-line { color: #6b8674; font-size: 12px; margin-top: 2px; }
.ride-stops { list-style: none; margin: 6px 0; padding: 0; color: #6b8674; font-size: 12px; }
.ride-stops li { padding: 2px 0 2px 12px; position: relative; }
.ride-stops li::before { content: ""; position: absolute; left: 0; top: 8px; width: 5px; height: 5px; border-radius: 50%; background: #b7c9be; }
.ride-stops-missing, .ride-notice { color: #8b9690; font-size: 12px; }
.ride-alight { margin-top: 4px; }
</style>
