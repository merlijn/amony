import {BrowserRouter, Route, Routes, useParams} from 'react-router-dom';
import React, {lazy, Suspense, useMemo, use} from 'react';
import {Constants, SessionContext} from "./api/Constants";
import {getSession} from "./api/generated";
import {AxiosError} from "axios";
import {SessionInfo} from "./api/Model";
import {ThemeProvider} from "./ThemeContext";
import {EventBusProvider} from "./components/common/EventBus";
import LoginPage from "./pages/LoginPage";

const Compilation = lazy(() => import('./pages/Compilation'));
const Main = lazy(() => import('./pages/Main'));
const VideoWall = lazy(() => import('./pages/VideoWall'));

function App() {
  const sessionPromise = useMemo(() =>
      getSession()
        .then((authToken) => ({
          isLoggedIn: () => authToken.userId !== "anonymous",
          isAdmin: () => authToken.roles.includes("admin")
        } as SessionInfo))
        .catch((error: AxiosError) => {
          if (error.response?.status === 401) {
            // Login is required and the user is not authenticated.
            return null;
          }
          console.log("Error getting session", error);
          return Constants.anonymousSession;
        }),
    []);

  return (
    <ThemeProvider>
      <div className="app-root">
        <BrowserRouter>
          <Suspense fallback={<div />}>
            <EventBusProvider>
              <SessionGate sessionPromise={sessionPromise} />
            </EventBusProvider>
          </Suspense>
        </BrowserRouter>
      </div>
    </ThemeProvider>
  );
}

function SessionGate({ sessionPromise }: { sessionPromise: Promise<SessionInfo | null> }) {
  const session = use(sessionPromise);

  if (session === null) {
    return <LoginPage />;
  }

  return (
    <SessionContext.Provider value={session}>
      <Routes>
        <Route path="/" element={<Main />} />
        <Route path="/search" element={<Main />} />
        <Route path="/video-wall" element={<VideoWall />} />
        <Route path="/compilation" element={<Compilation />} />
      </Routes>
    </SessionContext.Provider>
  );
}

export default App;
