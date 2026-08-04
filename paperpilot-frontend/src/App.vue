<template>
  <div class="app-stage">
    <div class="ambient-noise"></div>

    <header class="navbar">
      <div class="nav-content">
        <div class="brand">
          <span class="brand-icon">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
              <polyline points="14 2 14 8 20 8"></polyline>
              <line x1="16" y1="13" x2="8" y2="13"></line>
              <line x1="16" y1="17" x2="8" y2="17"></line>
              <polyline points="10 9 9 9 8 9"></polyline>
            </svg>
          </span>
          <span class="brand-name">Paper<span class="brand-accent">Pilot</span></span>
          <span class="beta-badge">BETA</span>
        </div>
        <div class="nav-subtitle">论文复现智能助手</div>
      </div>
    </header>

    <main class="main-container">
      <!-- ====== 输入区 ====== -->
      <section class="input-section" v-if="!taskState || taskState.stage === 'IDLE'">
        <div class="hero-card">
          <h1 class="hero-title">让论文复现不再困难</h1>
          <p class="hero-desc">
            输入论文 PDF + GitHub 仓库链接，AI Agent 自动完成<br/>
            论文理解 → 代码分析 → 概念映射 → 环境生成
          </p>

          <div class="input-card">
            <!-- PDF 上传 -->
            <div class="upload-row">
              <div
                class="upload-zone"
                :class="{ 'is-dragover': isDragOver }"
                @dragenter.prevent="isDragOver = true"
                @dragover.prevent="isDragOver = true"
                @dragleave.prevent="isDragOver = false"
                @drop.prevent="handleDrop"
                @click="$refs.fileInput.click()"
              >
                <div class="upload-icon">
                  <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
                    <polyline points="17 8 12 3 7 8"></polyline>
                    <line x1="12" y1="3" x2="12" y2="15"></line>
                  </svg>
                </div>
                <div class="upload-text">
                  <template v-if="pdfFile">
                    <span class="upload-filename">{{ pdfFile.name }}</span>
                    <span class="upload-size">{{ formatSize(pdfFile.size) }}</span>
                  </template>
                  <template v-else>
                    <span class="upload-title">上传论文 PDF</span>
                    <span class="upload-hint">点击选择或拖拽文件到此处</span>
                  </template>
                </div>
                <input
                  ref="fileInput"
                  type="file"
                  accept=".pdf"
                  hidden
                  @change="handleFileChange"
                />
              </div>

              <button
                v-if="pdfFile"
                class="clear-btn"
                @click.stop="pdfFile = null"
                title="清除文件"
              >✕</button>
            </div>

            <!-- GitHub URL -->
            <div class="url-row">
              <div class="url-input-box">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M9 19c-5 1.5-5-2.5-7-3m14 6v-3.87a3.37 3.37 0 0 0-.94-2.61c3.14-.35 6.44-1.54 6.44-7A5.44 5.44 0 0 0 20 4.77 5.07 5.07 0 0 0 19.91 1S18.73.65 16 2.48a13.38 13.38 0 0 0-7 0C6.27.65 5.09 1 5.09 1A5.07 5.07 0 0 0 5 4.77a5.44 5.44 0 0 0-1.5 3.78c0 5.42 3.3 6.61 6.44 7A3.37 3.37 0 0 0 9 18.13V22"></path>
                </svg>
                <input
                  v-model="githubUrl"
                  type="url"
                  placeholder="GitHub 仓库链接（可选）例如 https://github.com/yuqinie98/PatchTST"
                  autocomplete="off"
                  spellcheck="false"
                />
              </div>
            </div>

            <!-- 分析模式 -->
            <div class="mode-row">
              <span class="mode-label">分析模式：</span>
              <div class="mode-options">
                <label
                  v-for="opt in analysisModes"
                  :key="opt.value"
                  class="mode-option"
                  :class="{ active: analysisMode === opt.value }"
                >
                  <input type="radio" v-model="analysisMode" :value="opt.value" />
                  <span class="mode-title">{{ opt.title }}</span>
                  <span class="mode-desc">{{ opt.desc }}</span>
                </label>
              </div>
            </div>

            <!-- 提交 -->
            <button
              class="submit-btn"
              :disabled="!pdfFile || submitting"
              @click="submitAnalysis"
            >
              <template v-if="submitting">
                <span class="spinner"></span>
                提交中...
              </template>
              <template v-else>
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <polygon points="5 3 19 12 5 21 5 3"></polygon>
                </svg>
                开始分析
              </template>
            </button>
          </div>
        </div>
      </section>

      <!-- ====== 进度区 ====== -->
      <section class="progress-section" v-if="taskState && taskState.stage !== 'IDLE'">
        <div class="progress-card">
          <div class="task-id">Task #{{ taskId }}</div>

          <!-- 阶段管道 -->
          <div class="stage-pipeline">
            <div
              v-for="(stage, idx) in stages"
              :key="stage.key"
              class="stage-node"
              :class="{
                'is-done': stage.status === 'done',
                'is-active': stage.status === 'active',
                'is-pending': stage.status === 'pending',
                'is-error': stage.status === 'error'
              }"
            >
              <div class="stage-dot">
                <template v-if="stage.status === 'done'">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><polyline points="20 6 9 17 4 12"></polyline></svg>
                </template>
                <template v-else-if="stage.status === 'active'">
                  <span class="pulse-dot"></span>
                </template>
                <template v-else-if="stage.status === 'error'">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
                </template>
                <template v-else>
                  <span class="empty-dot"></span>
                </template>
              </div>
              <span class="stage-label">{{ stage.label }}</span>
              <div class="stage-connector" v-if="idx < stages.length - 1"
                :class="{ 'is-filled': stage.status === 'done' }"></div>
            </div>
          </div>

          <!-- 进度条 -->
          <div class="progress-bar-wrap">
            <div class="progress-bar" :style="{ width: taskState.progress + '%' }"></div>
          </div>
          <div class="progress-message">{{ taskState.message || '准备中...' }}</div>

          <!-- 错误信息 -->
          <div class="error-box" v-if="taskState.stage === 'FAILED'">
            <div class="error-title">分析失败</div>
            <div class="error-detail">{{ taskState.error || '未知错误' }}</div>
            <button class="retry-btn" @click="resetAndRetry">重新分析</button>
          </div>
        </div>
      </section>

      <!-- ====== 结果区 ====== -->
      <section class="result-section" v-if="result">
        <div class="result-card">
          <!-- Tab 导航 -->
          <div class="result-tabs">
            <button
              v-for="tab in resultTabs"
              :key="tab.key"
              class="result-tab"
              :class="{ active: activeTab === tab.key }"
              @click="activeTab = tab.key"
            >
              <span class="tab-icon">{{ tab.icon }}</span>
              <span class="tab-label">{{ tab.label }}</span>
            </button>
          </div>

          <!-- Tab 内容 -->
          <div class="result-content">
            <!-- 论文总结 -->
            <div class="tab-panel" v-if="activeTab === 'paper'">
              <div class="panel-header">
                <h2>{{ result.paper?.title || '论文分析结果' }}</h2>
              </div>
              <div class="structured-card" v-if="result.paper">
                <div class="field" v-if="result.paper.problem">
                  <span class="field-label">研究问题</span>
                  <span class="field-value">{{ result.paper.problem }}</span>
                </div>
                <div class="field" v-if="result.paper.innovation?.length">
                  <span class="field-label">创新点</span>
                  <ul class="field-list">
                    <li v-for="(item, i) in result.paper.innovation" :key="i">{{ item }}</li>
                  </ul>
                </div>
                <div class="field" v-if="result.paper.method?.length">
                  <span class="field-label">核心方法</span>
                  <div class="method-list">
                    <div class="method-item" v-for="(m, i) in result.paper.method" :key="i">
                      <span class="method-name">{{ m.name }}</span>
                      <span class="method-desc">{{ m.description }}</span>
                    </div>
                  </div>
                </div>
                <div class="field" v-if="result.paper.dataset?.length">
                  <span class="field-label">数据集</span>
                  <div class="tag-row">
                    <span class="tag" v-for="(d, i) in result.paper.dataset" :key="i">{{ d }}</span>
                  </div>
                </div>
              </div>
              <div class="raw-markdown" v-html="renderedPaperSummary" v-if="result.paper?.rawSummary"></div>
            </div>

            <!-- 代码结构 -->
            <div class="tab-panel" v-if="activeTab === 'code'">
              <div class="panel-header">
                <h2>代码仓库分析</h2>
              </div>
              <div v-if="result.code" class="code-structure">
                <div class="repo-url" v-if="result.code.repoUrl">
                  <a :href="result.code.repoUrl" target="_blank">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 19c-5 1.5-5-2.5-7-3m14 6v-3.87a3.37 3.37 0 0 0-.94-2.61c3.14-.35 6.44-1.54 6.44-7A5.44 5.44 0 0 0 20 4.77 5.07 5.07 0 0 0 19.91 1S18.73.65 16 2.48a13.38 13.38 0 0 0-7 0C6.27.65 5.09 1 5.09 1A5.07 5.07 0 0 0 5 4.77a5.44 5.44 0 0 0-1.5 3.78c0 5.42 3.3 6.61 6.44 7A3.37 3.37 0 0 0 9 18.13V22"></path></svg>
                    {{ result.code.repoUrl }}
                  </a>
                </div>
                <div class="file-tree" v-if="result.code.structure">
                  <pre>{{ result.code.structure }}</pre>
                </div>
                <div class="core-modules" v-if="result.code.modules?.length">
                  <h3>核心模块</h3>
                  <div class="module-card" v-for="(mod, i) in result.code.modules" :key="i">
                    <div class="module-header">
                      <span class="module-name">{{ mod.name }}</span>
                      <span class="module-rank" v-if="mod.pageRank">PageRank: {{ mod.pageRank }}</span>
                    </div>
                    <div class="module-desc">{{ mod.description }}</div>
                  </div>
                </div>
              </div>
              <div class="empty-tab" v-else>
                <p>未提供 GitHub 链接或代码分析尚未完成</p>
              </div>
            </div>

            <!-- 概念映射 -->
            <div class="tab-panel" v-if="activeTab === 'mapping'">
              <div class="panel-header">
                <h2>论文-代码映射</h2>
              </div>
              <div v-if="result.mapping?.length">
                <table class="mapping-table">
                  <thead>
                    <tr>
                      <th>论文概念</th>
                      <th>代码位置</th>
                      <th>置信度</th>
                      <th>说明</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(m, i) in result.mapping" :key="i"
                      :class="{ 'low-confidence': m.confidence < 0.7 }">
                      <td class="concept-cell">{{ m.paperConcept }}</td>
                      <td class="code-cell"><code>{{ m.codeLocation }}</code></td>
                      <td>
                        <span class="confidence-badge" :class="confidenceClass(m.confidence)">
                          {{ (m.confidence * 100).toFixed(0) }}%
                        </span>
                      </td>
                      <td class="explain-cell">{{ m.explanation }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <div class="empty-tab" v-else>
                <p>映射结果尚未生成</p>
              </div>
            </div>

            <!-- 运行环境 -->
            <div class="tab-panel" v-if="activeTab === 'env'">
              <div class="panel-header">
                <h2>运行环境配置</h2>
              </div>
              <div v-if="result.env">
                <div class="dockerfile-block" v-if="result.env.dockerfile">
                  <h3>Dockerfile</h3>
                  <pre class="code-block"><code>{{ result.env.dockerfile }}</code></pre>
                  <button class="copy-btn" @click="copyText(result.env.dockerfile)">
                    {{ copied ? '已复制!' : '复制' }}
                  </button>
                </div>
                <div class="run-steps" v-if="result.env.steps?.length">
                  <h3>运行步骤</h3>
                  <ol class="step-list">
                    <li v-for="(step, i) in result.env.steps" :key="i">{{ step }}</li>
                  </ol>
                </div>
              </div>
              <div class="empty-tab" v-else>
                <p>环境配置尚未生成</p>
              </div>
            </div>
          </div>
        </div>
      </section>
    </main>

    <!-- Toast 消息 -->
    <div class="toast" :class="{ visible: toast.visible }" role="alert">{{ toast.message }}</div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, nextTick } from 'vue'
import { apiRequest } from './api.js'
import { createTaskStreams } from './taskEvents.js'
import { renderMarkdown } from './markdown.js'

// ====== 状态 ======
const pdfFile = ref(null)
const githubUrl = ref('')
const analysisMode = ref('full')
const isDragOver = ref(false)
const submitting = ref(false)
const taskId = ref(null)
const taskState = ref(null)
const result = ref(null)
const activeTab = ref('paper')
const copied = ref(false)
const toast = reactive({ visible: false, message: '', timer: null })

const analysisModes = [
  { value: 'full', title: '完整分析', desc: '论文+代码+映射+环境' },
  { value: 'paper-only', title: '仅论文', desc: '只解析论文结构和总结' }
]

const stages = [
  { key: 'PAPER_ANALYSIS', label: '论文理解' },
  { key: 'CODE_ANALYSIS',  label: '代码分析' },
  { key: 'MAPPING',        label: '概念映射' },
  { key: 'ENV_SETUP',      label: '环境生成' }
]

const STAGE_KEYS = ['PAPER_ANALYSIS', 'CODE_ANALYSIS', 'MAPPING', 'ENV_SETUP']

const resultTabs = [
  { key: 'paper',   icon: '📄', label: '论文总结' },
  { key: 'code',    icon: '💻', label: '代码结构' },
  { key: 'mapping', icon: '🔗', label: '概念映射' },
  { key: 'env',     icon: '🐳', label: '运行环境' }
]

// ====== SSE ======
const { start: startSSE, stop: stopSSE, stopAll } = createTaskStreams({
  onActiveChange: () => {}
})

// ====== 计算属性 ======
const renderedPaperSummary = computed(() => {
  if (!result.value?.paper?.rawSummary) return ''
  return renderMarkdown(result.value.paper.rawSummary)
})

function computeStageStatus(stageKey) {
  if (!taskState.value) return 'pending'
  const currentIdx = STAGE_KEYS.indexOf(taskState.value.stage)
  const stageIdx = STAGE_KEYS.indexOf(stageKey)

  if (taskState.value.stage === 'FAILED') {
    // 标记当前阶段为 error
    return stageIdx === currentIdx ? 'error'
      : stageIdx < currentIdx ? 'done' : 'pending'
  }
  if (taskState.value.stage === 'COMPLETED') return 'done'
  if (stageIdx < currentIdx) return 'done'
  if (stageIdx === currentIdx) return 'active'
  return 'pending'
}

function confidenceClass(confidence) {
  if (confidence >= 0.85) return 'high'
  if (confidence >= 0.7) return 'medium'
  return 'low'
}

// ====== 方法 ======
function formatSize(bytes) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

function showToast(msg) {
  if (toast.timer) clearTimeout(toast.timer)
  toast.message = msg
  toast.visible = true
  toast.timer = setTimeout(() => { toast.visible = false }, 3000)
}

function handleFileChange(e) {
  const file = e.target.files?.[0]
  if (file && file.type === 'application/pdf') {
    pdfFile.value = file
  } else if (file) {
    showToast('请选择 PDF 文件')
  }
}

function handleDrop(e) {
  isDragOver.value = false
  const file = e.dataTransfer.files?.[0]
  if (file && file.type === 'application/pdf') {
    pdfFile.value = file
  } else if (file) {
    showToast('请上传 PDF 文件')
  }
}

async function submitAnalysis() {
  if (!pdfFile.value) return
  submitting.value = true

  try {
    // 1. 上传 PDF
    const formData = new FormData()
    formData.append('file', pdfFile.value)

    const uploadRes = await apiRequest('/api/papers/upload', {
      method: 'POST',
      body: formData
    })
    if (!uploadRes.ok) throw new Error(await uploadRes.text())
    const paperData = await uploadRes.json()

    // 2. 提交分析任务
    const taskBody = {
      paperId: paperData.id,
      githubUrl: githubUrl.value || null,
      mode: analysisMode.value
    }
    const taskRes = await apiRequest('/api/tasks', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(taskBody)
    })
    if (!taskRes.ok) throw new Error(await taskRes.text())
    const taskData = await taskRes.json()
    taskId.value = taskData.id

    // 3. 初始化进度状态
    taskState.value = { stage: 'PAPER_ANALYSIS', progress: 0, message: '任务已提交，等待处理...' }

    // 4. 订阅 SSE 进度
    startSSE(taskId.value, `/api/tasks/${taskData.id}/progress`, (event) => {
      taskState.value = {
        stage: event.stage,
        progress: event.progress || 0,
        message: event.message || ''
      }

      // 完成时拉取结果
      if (event.stage === 'COMPLETED') {
        fetchResult(taskData.id)
      }
      if (event.stage === 'FAILED') {
        taskState.value.error = event.message || '分析失败'
      }
    }, (error, attempt, terminal) => {
      if (terminal) {
        taskState.value = { stage: 'FAILED', progress: 0, message: 'SSE 连接断开', error: error.message }
      }
    })

  } catch (err) {
    showToast(err.message || '提交失败')
    taskState.value = null
  } finally {
    submitting.value = false
  }
}

async function fetchResult(id) {
  try {
    const res = await apiRequest(`/api/tasks/${id}/result`)
    if (res.ok) result.value = await res.json()
  } catch (err) {
    console.error('Failed to fetch result:', err)
  }
}

function resetAndRetry() {
  stopAll()
  taskId.value = null
  taskState.value = null
  result.value = null
  activeTab.value = 'paper'
}

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text)
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  } catch {
    showToast('复制失败，请手动复制')
  }
}
</script>
