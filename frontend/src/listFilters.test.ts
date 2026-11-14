import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  EMPTY_COMPARISON_FILTER,
  EMPTY_EVALUATION_FILTER,
  EMPTY_LEADERBOARD_FILTER,
  EMPTY_PUBLIC_BOARD_FILTER,
  EMPTY_REGRESSION_FILTER,
  comparisonFiltersActive,
  comparisonSearchParams,
  costSearchParams,
  evaluationFiltersActive,
  evaluationSearchParams,
  filterComparisonRows,
  filterCostAgents,
  filterEvaluationItems,
  filterLeaderboardRows,
  filterPublicEntries,
  filterRegressionRuns,
  leaderboardFiltersActive,
  leaderboardSearchParams,
  publicBoardFiltersActive,
  regressionFiltersActive,
} from './listFilters.ts'

const evalItems = [
  {
    id: 'aaaaaaaa-1111',
    state: 'COMPLETED',
    agentId: 'agent-a',
    agentName: 'Fake Fixer',
    taskId: 'task-1',
    taskSlug: 'null-deref',
    benchmarkVersionId: 'ver-1',
    benchmarkVersion: '1.0.0',
    failureClass: null,
  },
  {
    id: 'bbbbbbbb-2222',
    state: 'FAILED',
    agentId: 'agent-b',
    agentName: 'Baseline Stub',
    taskId: 'task-2',
    taskSlug: 'off-by-one',
    benchmarkVersionId: 'ver-2',
    benchmarkVersion: '0.9.0',
    failureClass: 'AGENT',
  },
  {
    id: 'cccccccc-3333',
    state: 'COMPLETED',
    agentId: 'agent-a',
    agentName: 'Fake Fixer',
    taskId: 'task-2',
    taskSlug: 'off-by-one',
    benchmarkVersionId: 'ver-1',
    benchmarkVersion: '1.0.0',
    failureClass: null,
  },
]

test('empty evaluation filters omit every constraint and keep the full list', () => {
  const params = evaluationSearchParams(EMPTY_EVALUATION_FILTER, 0)
  assert.equal(params.get('page'), '0')
  assert.equal(params.get('size'), '20')
  assert.equal(params.get('q'), null)
  assert.equal(params.get('state'), null)
  assert.equal(params.get('agentId'), null)
  assert.equal(params.get('taskId'), null)
  assert.equal(params.get('benchmarkVersionId'), null)
  assert.equal(evaluationFiltersActive(EMPTY_EVALUATION_FILTER), false)
  assert.deepEqual(filterEvaluationItems(evalItems, EMPTY_EVALUATION_FILTER), evalItems)
})

test('whitespace-only search is an empty evaluation filter', () => {
  const filter = { ...EMPTY_EVALUATION_FILTER, q: '   ' }
  assert.equal(evaluationFiltersActive(filter), false)
  assert.equal(evaluationSearchParams(filter, 0).get('q'), null)
  assert.equal(filterEvaluationItems(evalItems, filter).length, evalItems.length)
})

test('evaluation filters combine and a clear restores every row', () => {
  const combined = {
    q: 'fixer',
    state: 'COMPLETED',
    agentId: 'agent-a',
    taskId: 'task-2',
    benchmarkVersionId: 'ver-1',
  }
  const params = evaluationSearchParams(combined, 2, 20)
  assert.equal(params.get('q'), 'fixer')
  assert.equal(params.get('state'), 'COMPLETED')
  assert.equal(params.get('agentId'), 'agent-a')
  assert.equal(params.get('taskId'), 'task-2')
  assert.equal(params.get('benchmarkVersionId'), 'ver-1')
  assert.equal(params.get('page'), '2')
  const matched = filterEvaluationItems(evalItems, combined)
  assert.deepEqual(matched.map((item) => item.id), ['cccccccc-3333'])
  assert.equal(filterEvaluationItems(evalItems, { ...combined, state: 'FAILED' }).length, 0)
  assert.deepEqual(filterEvaluationItems(evalItems, EMPTY_EVALUATION_FILTER), evalItems)
})

test('evaluation search matches agent, task, and id without dropping other constraints', () => {
  assert.equal(
    filterEvaluationItems(evalItems, { ...EMPTY_EVALUATION_FILTER, q: 'NULL-deref' }).length,
    1,
  )
  assert.equal(
    filterEvaluationItems(evalItems, { ...EMPTY_EVALUATION_FILTER, q: 'bbbbbbbb' }).length,
    1,
  )
  assert.equal(
    filterEvaluationItems(evalItems, {
      ...EMPTY_EVALUATION_FILTER,
      q: 'fixer',
      state: 'FAILED',
    }).length,
    0,
  )
})

const boardRows = [
  {
    agentId: 'agent-a',
    agentName: 'Fake Fixer',
    adapterType: 'FAKE',
    taskId: 'task-1',
    taskSlug: 'null-deref',
  },
  {
    agentId: 'agent-b',
    agentName: 'Baseline Stub',
    adapterType: 'FAKE',
    taskId: 'task-2',
    taskSlug: 'off-by-one',
  },
]

test('empty leaderboard filters omit constraints and keep every row', () => {
  const params = leaderboardSearchParams(EMPTY_LEADERBOARD_FILTER)
  assert.equal(params.get('taskId'), null)
  assert.equal(params.get('agentId'), null)
  assert.equal(params.get('q'), null)
  assert.equal(leaderboardFiltersActive(EMPTY_LEADERBOARD_FILTER), false)
  assert.deepEqual(filterLeaderboardRows(boardRows, EMPTY_LEADERBOARD_FILTER), boardRows)
})

test('leaderboard filters combine, miss, and clear', () => {
  const combined = { q: 'stub', agentId: 'agent-b', taskId: 'task-2' }
  const params = leaderboardSearchParams(combined, 0, 50)
  assert.equal(params.get('q'), 'stub')
  assert.equal(params.get('agentId'), 'agent-b')
  assert.equal(params.get('taskId'), 'task-2')
  assert.deepEqual(
    filterLeaderboardRows(boardRows, combined).map((row) => row.agentId),
    ['agent-b'],
  )
  assert.equal(
    filterLeaderboardRows(boardRows, { ...combined, taskId: 'task-1' }).length,
    0,
  )
  assert.deepEqual(filterLeaderboardRows(boardRows, EMPTY_LEADERBOARD_FILTER), boardRows)
})

const publicEntries = [
  { agentName: 'Fake Fixer', adapterType: 'FAKE', taskSlug: 'null-deref' },
  { agentName: 'Baseline Stub', adapterType: 'HTTP', taskSlug: 'off-by-one' },
]

test('public leaderboard filters combine and clear', () => {
  assert.equal(publicBoardFiltersActive(EMPTY_PUBLIC_BOARD_FILTER), false)
  assert.deepEqual(filterPublicEntries(publicEntries, EMPTY_PUBLIC_BOARD_FILTER), publicEntries)
  const combined = { q: 'fake', agent: 'Fake Fixer', task: 'null-deref' }
  assert.equal(filterPublicEntries(publicEntries, combined).length, 1)
  assert.equal(
    filterPublicEntries(publicEntries, { ...combined, task: 'off-by-one' }).length,
    0,
  )
  assert.equal(filterPublicEntries(publicEntries, { q: '   ', agent: '', task: '' }).length, 2)
})

const runs = [
  {
    suiteName: 'selenium',
    gitBranch: 'main',
    gitSha: 'abc1234',
    gitRepo: 'https://github.com/Sai-333/agentbench',
    passed: 3,
    failed: 0,
    skipped: 1,
  },
  {
    suiteName: 'selenium',
    gitBranch: 'topic',
    gitSha: 'def5678',
    gitRepo: 'https://github.com/Sai-333/agentbench',
    passed: 1,
    failed: 2,
    skipped: 0,
  },
]

test('regression filters combine and clear', () => {
  assert.equal(regressionFiltersActive(EMPTY_REGRESSION_FILTER), false)
  assert.equal(filterRegressionRuns(runs, EMPTY_REGRESSION_FILTER).length, 2)
  assert.equal(
    filterRegressionRuns(runs, { q: 'topic', outcome: 'failed' }).length,
    1,
  )
  assert.equal(filterRegressionRuns(runs, { q: 'topic', outcome: 'passed' }).length, 0)
  assert.equal(filterRegressionRuns(runs, { q: 'selenium', outcome: 'skipped' }).length, 1)
  assert.equal(filterRegressionRuns(runs, EMPTY_REGRESSION_FILTER).length, 2)
})

const compareRows = [
  { agentId: 'agent-a', agentName: 'Fake Fixer', taskId: 'task-1', taskSlug: 'null-deref' },
  { agentId: 'agent-b', agentName: 'Baseline Stub', taskId: 'task-2', taskSlug: 'off-by-one' },
]

test('comparison and cost filters combine and clear', () => {
  assert.equal(comparisonFiltersActive(EMPTY_COMPARISON_FILTER), false)
  const params = comparisonSearchParams('ver-1', ['agent-a', 'agent-b'], '')
  assert.equal(params.get('taskId'), null)
  assert.equal(params.get('agentIds'), 'agent-a,agent-b')
  assert.equal(comparisonSearchParams('ver-1', ['agent-a'], 'task-2').get('taskId'), 'task-2')
  assert.equal(
    filterComparisonRows(compareRows, { q: 'stub', taskId: 'task-2' }).length,
    1,
  )
  assert.equal(filterComparisonRows(compareRows, { q: 'stub', taskId: 'task-1' }).length, 0)
  assert.equal(filterComparisonRows(compareRows, EMPTY_COMPARISON_FILTER).length, 2)

  const costs = [
    { agentId: 'agent-a', agentName: 'Fake Fixer' },
    { agentId: 'agent-b', agentName: 'Baseline Stub' },
  ]
  assert.equal(costSearchParams('').toString(), '')
  assert.equal(costSearchParams('agent-a').get('agentId'), 'agent-a')
  assert.deepEqual(filterCostAgents(costs, '', ''), costs)
  assert.deepEqual(
    filterCostAgents(costs, 'agent-a', 'fixer').map((row) => row.agentId),
    ['agent-a'],
  )
  assert.equal(filterCostAgents(costs, 'agent-a', 'stub').length, 0)
  assert.equal(filterCostAgents(costs, '', '  ').length, 2)
})
