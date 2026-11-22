import type { QualityFinding, QualityToolSummary } from './api'

const PRIMARY_NAMES: Record<string, string> = {
  checkstyle: 'Checkstyle',
  pmd: 'PMD',
  spotbugs: 'SpotBugs',
}

export type QualityFindingLine = {
  key: string
  text: string
}

export function qualityToolDisplayName(tool: QualityToolSummary): string {
  if (tool.notes === 'builtin_java_heuristics') return 'builtin_java_heuristics'
  return tool.tool
}

export function qualityToolNote(tool: QualityToolSummary): string {
  if (tool.tool === 'builtin_java_heuristics' || tool.notes === 'builtin_java_heuristics') {
    return 'builtin_java_heuristics'
  }
  const proper = PRIMARY_NAMES[tool.tool]
  if (proper && tool.stub) return `${proper} did not run`
  if (tool.stub) return 'stub'
  return tool.notes ?? ''
}

export function formatQualityFinding(toolName: string, finding: QualityFinding): string {
  const disposition = finding.introduced ? 'new' : 'pre-existing'
  const file = finding.filePath ? `${finding.filePath}:${finding.line}` : ''
  return `[${toolName}] ${disposition} · ${finding.ruleId} · ${file} — ${finding.message}`
}

export function qualityFindingLines(tools: QualityToolSummary[]): QualityFindingLine[] {
  const lines: QualityFindingLine[] = []
  tools.forEach((tool, toolIndex) => {
    const name = findingToolName(tool)
    ;(tool.findingsSample ?? []).forEach((finding, index) => {
      const named = finding.tool && finding.tool.length > 0 ? finding.tool : name
      const toolName = name === 'builtin_java_heuristics' ? 'builtin_java_heuristics' : named
      lines.push({
        key: `${toolName}-${finding.ruleId}-${finding.filePath}-${finding.line}-${finding.introduced}-${index}-${toolIndex}`,
        text: formatQualityFinding(toolName, finding),
      })
    })
  })
  return lines
}

function findingToolName(tool: QualityToolSummary): string {
  if (tool.notes === 'builtin_java_heuristics' || tool.tool === 'builtin_java_heuristics') {
    return 'builtin_java_heuristics'
  }
  return tool.tool
}
