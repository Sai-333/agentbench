import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  formatQualityFinding,
  qualityFindingLines,
  qualityToolDisplayName,
  qualityToolNote,
} from './qualityFindings.ts'
import type { QualityToolSummary } from './api.ts'

test('a finding names its tool, file, rule, and message, and is marked new or pre-existing', () => {
  const text = formatQualityFinding('checkstyle', {
    tool: 'checkstyle',
    ruleId: 'LineLengthCheck',
    severity: 'WARNING',
    filePath: 'src/Sample.java',
    line: 4,
    message: 'Line is longer than 100 characters',
    introduced: false,
  })
  assert.match(text, /\[checkstyle\]/)
  assert.match(text, /pre-existing/)
  assert.match(text, /LineLengthCheck/)
  assert.match(text, /src\/Sample\.java:4/)
  assert.match(text, /Line is longer than 100 characters/)

  const introduced = formatQualityFinding('spotbugs', {
    ruleId: 'ES_COMPARING_STRINGS_WITH_EQ',
    severity: 'WARNING',
    filePath: 'src/Sample.java',
    line: 3,
    message: 'Comparison of String objects using == or !=',
    introduced: true,
  })
  assert.match(introduced, /\[spotbugs\] new/)
  assert.match(introduced, /ES_COMPARING_STRINGS_WITH_EQ/)
})

test('a primary tool that did not run is named, and heuristics are not labeled as that tool', () => {
  const spotbugs: QualityToolSummary = {
    tool: 'spotbugs',
    stub: true,
    notes: 'did_not_run',
    totalFindings: 0,
    newFindings: 0,
    preExistingFindings: 0,
    findingsSample: [],
  }
  assert.equal(qualityToolNote(spotbugs), 'SpotBugs did not run')
  assert.equal(qualityToolDisplayName(spotbugs), 'spotbugs')

  const mislabeled: QualityToolSummary = {
    tool: 'checkstyle',
    stub: false,
    notes: 'builtin_java_heuristics',
    totalFindings: 43,
    newFindings: 0,
    preExistingFindings: 43,
    findingsSample: [
      {
        ruleId: 'TodoComment',
        severity: 'INFO',
        filePath: 'src/A.java',
        line: 1,
        message: 'TODO',
        introduced: false,
      },
    ],
  }
  assert.equal(qualityToolDisplayName(mislabeled), 'builtin_java_heuristics')
  assert.equal(qualityToolNote(mislabeled), 'builtin_java_heuristics')
  const relabeled = qualityFindingLines([mislabeled])
  assert.equal(relabeled.length, 1)
  assert.match(relabeled[0].text, /\[builtin_java_heuristics\]/)
  assert.doesNotMatch(relabeled[0].text, /\[checkstyle\]/)

  const heuristic: QualityToolSummary = {
    tool: 'builtin_java_heuristics',
    stub: false,
    notes: 'builtin_java_heuristics',
    totalFindings: 1,
    newFindings: 1,
    preExistingFindings: 0,
    findingsSample: [
      {
        tool: 'builtin_java_heuristics',
        ruleId: 'RegexpSinglelineJava.SystemOut',
        severity: 'WARNING',
        filePath: 'src/A.java',
        line: 2,
        message: 'System.out usage',
        introduced: true,
      },
    ],
  }
  const lines = qualityFindingLines([spotbugs, heuristic])
  assert.equal(lines.length, 1)
  assert.match(lines[0].text, /\[builtin_java_heuristics\] new/)
  assert.doesNotMatch(lines[0].text, /\[checkstyle\]|\[pmd\]|\[spotbugs\]/)
})
