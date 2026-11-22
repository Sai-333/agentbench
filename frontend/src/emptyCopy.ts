/**
 * Signed-in empty states. Each one names the next action.
 * Selenium anchors must stay as substrings.
 */

export const EMPTY = {
  organizations: 'No organizations yet. Create one in the form below to get started.',
  selectOrgAnalytics: 'Select or create an organization on the left to view analytics.',
  selectOrgContinue: 'Select an organization on the left, or create one, to continue.',
  overview:
    'No evaluation activity yet for this organization. Enqueue an evaluation to see results here.',
  recentEvaluations: 'No recent evaluations. Open Evaluations and enqueue a run.',
  volume: 'No volume in the selected window. Enqueue an evaluation to fill the daily counts.',
  leaderboard:
    'No completed scored evaluations for this benchmark version yet. Publish a version and enqueue an evaluation to add a row.',
  leaderboardFiltered: 'No leaderboard rows match these filters. Clear the filters to see every row.',
  comparison:
    'Select agents and a published benchmark version to compare identical runs.',
  comparisonFiltered:
    'No comparison rows match these filters. Clear the filters or pick different agents.',
  costs:
    'No token or cost records yet. Costs appear when adapters report them. Run an evaluation to record usage.',
  costsFiltered: 'No cost rows match these filters. Clear the search to see every adapter.',
  costsByAgent:
    'No per-agent cost rows. Run an evaluation with an adapter that reports token usage.',
  costsByDay: 'No daily cost points yet. Run an evaluation to start the series.',
  health: 'Health summary unavailable. Use Refresh health to try again.',
  samlEmpty: 'No SAML IdPs configured for this organization. Add one in the form below.',
  samlForbidden: 'Only OWNER or ADMIN can manage SAML IdPs. Ask an owner to grant that role.',
  agents:
    'No agents registered yet. Register one in the form below, or enqueue an evaluation and leave the agent on the default Grok adapter.',
  agentsNeedOrg: 'Select or create an organization to manage agents.',
  evaluations:
    'No evaluations yet. Enqueue one below with a published version, a task, and an agent.',
  evaluationsFiltered:
    'No evaluations match these filters. Clear the filters or enqueue a new evaluation.',
  evaluationsNeedOrg: 'Select or create an organization to manage evaluations.',
  projects: 'No projects in this organization yet. Create one in the form below.',
  projectsNeedOrg: 'Select or create an organization to manage projects.',
  benchmarks: 'No benchmarks yet. Create one in the form below.',
  benchmarksNeedOrg: 'Select or create an organization to manage benchmarks.',
  versions: 'No versions yet. Create a draft version below, then add tasks and publish it.',
  tasksNeedVersion:
    'Select a version to manage tasks. Create a draft version if this list is empty.',
  tasks: 'No tasks in this version. Add a task below before you publish.',
  regression: 'No regression runs yet. Record a run from CI, then use Refresh runs.',
  regressionFiltered:
    'No regression runs match these filters. Clear the filters to see every run.',
  regressionForbidden:
    'Your account does not have OWNER or ADMIN on an organization, so run details stay hidden. Create an organization or ask an owner for access.',
  publicLeaderboard:
    'Published, but no completed scored evaluations yet. Ask an owner to publish a completed run.',
  publicFiltered: 'No leaderboard rows match these filters. Clear the filters to see every row.',
} as const

export const SELENIUM_EMPTY_ANCHORS = [
  'No evaluation activity yet',
  'No completed scored evaluations for this benchmark version yet.',
  'No tasks in this version',
] as const
