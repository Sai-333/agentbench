import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { PublicLeaderboardPage, publicLeaderboardSlugFromPath } from './PublicLeaderboardPage.tsx'

const publicSlug = publicLeaderboardSlugFromPath(window.location.pathname)

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {publicSlug ? <PublicLeaderboardPage slug={publicSlug} /> : <App />}
  </StrictMode>,
)
