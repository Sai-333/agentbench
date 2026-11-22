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
  costViewState,
  costsWereReported,
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
  assert.equal(params.get('failureClass'), null)
  assert.equal(params.get('minScore'), null)
  assert.equal(params.get('maxScore'), null)
  assert.equal(params.get('createdFrom'), null)
  assert.equal(params.get('createdTo'), null)
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

test('task and version are separate query parameters', () => {
  const taskId = '11111111-1111-4111-8111-111111111111'
  const benchmarkVersionId = '22222222-2222-4222-8222-222222222222'
  const params = evaluationSearchParams(
    { ...EMPTY_EVALUATION_FILTER, taskId, benchmarkVersionId },
    0,
  )
  assert.equal(params.get('taskId'), taskId)
  assert.equal(params.get('benchmarkVersionId'), benchmarkVersionId)
  const query = params.toString()
  assert.equal(query.includes(`${taskId}${benchmarkVersionId}`), false)
  assert.equal(query.includes(`taskId=${taskId}`), true)
  assert.equal(query.includes(`benchmarkVersionId=${benchmarkVersionId}`), true)
})

const scoredItems = [
  {
    ...evalItems[0],
    weightedTotal: 90,
    createdAt: '2026-11-01T00:00:00.000Z',
    failureClass: null,
  },
  {
    ...evalItems[1],
    weightedTotal: 40,
    createdAt: '2026-11-02T00:00:00.000Z',
    failureClass: 'AGENT',
  },
  {
    ...evalItems[2],
    weightedTotal: null,
    createdAt: '2026-11-03T00:00:00.000Z',
    failureClass: null,
  },
]

test('failure class, score, and created time combine and clear back to the other filters', () => {
  const cuts = {
    ...EMPTY_EVALUATION_FILTER,
    state: 'COMPLETED',
    agentId: 'agent-a',
    failureClass: 'NONE',
    minScore: '50',
    maxScore: '100',
    createdFrom: '2026-11-01T00:00:00.000Z',
    createdTo: '2026-11-01T23:59:59.000Z',
  }
  const params = evaluationSearchParams(cuts, 0)
  assert.equal(params.get('failureClass'), 'NONE')
  assert.equal(params.get('minScore'), '50')
  assert.equal(params.get('maxScore'), '100')
  assert.equal(params.get('createdFrom'), '2026-11-01T00:00:00.000Z')
  assert.equal(params.get('createdTo'), '2026-11-01T23:59:59.000Z')
  assert.equal(params.get('state'), 'COMPLETED')
  assert.deepEqual(
    filterEvaluationItems(scoredItems, cuts).map((item) => item.id),
    ['aaaaaaaa-1111'],
  )
  assert.equal(
    filterEvaluationItems(scoredItems, { ...cuts, minScore: '95' }).length,
    0,
  )
  assert.equal(
    filterEvaluationItems(scoredItems, {
      ...EMPTY_EVALUATION_FILTER,
      failureClass: 'AGENT',
    }).map((item) => item.id)[0],
    'bbbbbbbb-2222',
  )
  assert.equal(
    filterEvaluationItems(scoredItems, { ...EMPTY_EVALUATION_FILTER, minScore: '0' }).length,
    2,
  )
  assert.equal(
    filterEvaluationItems(scoredItems, { ...EMPTY_EVALUATION_FILTER, maxScore: '100' }).length,
    2,
  )

  const clearedCuts = {
    ...cuts,
    failureClass: '',
    minScore: '',
    maxScore: '',
    createdFrom: '',
    createdTo: '',
  }
  const clearedParams = evaluationSearchParams(clearedCuts, 0)
  assert.equal(clearedParams.get('failureClass'), null)
  assert.equal(clearedParams.get('minScore'), null)
  assert.equal(clearedParams.get('maxScore'), null)
  assert.equal(clearedParams.get('createdFrom'), null)
  assert.equal(clearedParams.get('createdTo'), null)
  assert.equal(clearedParams.get('state'), 'COMPLETED')
  assert.equal(clearedParams.get('agentId'), 'agent-a')
  assert.deepEqual(
    filterEvaluationItems(scoredItems, clearedCuts).map((item) => item.id),
    filterEvaluationItems(scoredItems, {
      ...EMPTY_EVALUATION_FILTER,
      state: 'COMPLETED',
      agentId: 'agent-a',
    }).map((item) => item.id),
  )
})

test('invalid score and time values stay out of the query', () => {
  const filter = {
    ...EMPTY_EVALUATION_FILTER,
    minScore: '  ',
    maxScore: 'nope',
    createdFrom: 'not-a-time',
    createdTo: '',
  }
  assert.equal(
    evaluationFiltersActive({ ...EMPTY_EVALUATION_FILTER, minScore: '   ', maxScore: '' }),
    false,
  )
  const params = evaluationSearchParams(filter, 0)
  assert.equal(params.get('minScore'), null)
  assert.equal(params.get('maxScore'), null)
  assert.equal(params.get('createdFrom'), null)
  assert.equal(params.get('createdTo'), null)
  assert.equal(filterEvaluationItems(scoredItems, filter).length, scoredItems.length)
})

test('a minute-precision created-to includes that minute', () => {
  const params = evaluationSearchParams(
    {
      ...EMPTY_EVALUATION_FILTER,
      createdFrom: '2026-11-02T00:00',
      createdTo: '2026-11-02T00:00',
    },
    0,
  )
  const from = Date.parse(params.get('createdFrom') ?? '')
  const to = Date.parse(params.get('createdTo') ?? '')
  assert.equal(to - from, 60_000 - 1)
  const inside = new Date(from + 30_000).toISOString()
  const outside = new Date(from + 60_000).toISOString()
  assert.equal(
    filterEvaluationItems(
      [{ ...scoredItems[1], createdAt: inside }],
      { ...EMPTY_EVALUATION_FILTER, createdFrom: '2026-11-02T00:00', createdTo: '2026-11-02T00:00' },
    ).length,
    1,
  )
  assert.equal(
    filterEvaluationItems(
      [{ ...scoredItems[1], createdAt: outside }],
      { ...EMPTY_EVALUATION_FILTER, createdFrom: '2026-11-02T00:00', createdTo: '2026-11-02T00:00' },
    ).length,
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
  assert.equal(costsWereReported(0, 0), false)
  assert.equal(costsWereReported(0, 0.01), true)
  assert.equal(costsWereReported(12, 0), true)
  assert.equal(costViewState(false, false, 1), 'empty')
  assert.equal(costViewState(false, true, 0), 'filtered')
  assert.equal(costViewState(true, true, 0), 'filtered')
  assert.equal(costViewState(true, false, 2), 'ready')
  assert.equal(costViewState(true, true, 1), 'ready')
})
