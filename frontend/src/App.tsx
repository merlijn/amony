import {BrowserRouter, Route, Routes, useParams} from 'react-router-dom';
import React, {lazy, Suspense, useMemo, use} from 'react';
import {Constants, SessionContext} from "./api/Constants";
import {ConfigContext, AppConfig} from "./api/ConfigContext";
import {pickSupportedImageFormat} from "./api/MediaFormat";
import {getConfig, getSession} from "./api/generated";
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
          userId: authToken.userId,
          roles: authToken.roles,
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

  const configPromise = useMemo(() =>
      getConfig()
        .then(async (config): Promise<AppConfig> => ({
          ...config,
          // The most preferred format this browser can actually decode; resolved once at startup.
          imageFormat: await pickSupportedImageFormat(config.supportedFormats),
        }))
        .catch((error: AxiosError) => {
          // Without config the frontend still works, it just falls back to the default thumbnail resolution and format.
          console.log("Error getting app config", error);
          return undefined;
        }),
    []);

  return (
    <ThemeProvider>
      <div className="app-root">
        <BrowserRouter>
          <Suspense fallback={<div />}>
            <EventBusProvider>
              <SessionGate sessionPromise={sessionPromise} configPromise={configPromise} />
            </EventBusProvider>
          </Suspense>
        </BrowserRouter>
      </div>
    </ThemeProvider>
  );
}

function SessionGate({ sessionPromise, configPromise }: {
  sessionPromise: Promise<SessionInfo | null>,
  configPromise: Promise<AppConfig | undefined>
}) {
  const session = use(sessionPromise);
  const config  = use(configPromise);

  if (session === null) {
    return <LoginPage />;
  }

  return (
    <ConfigContext.Provider value={config}>
      <SessionContext.Provider value={session}>
        <Routes>
          <Route path="/" element={<Main />} />
          <Route path="/search" element={<Main />} />
          <Route path="/video-wall" element={<VideoWall />} />
          <Route path="/compilation" element={<Compilation />} />
        </Routes>
      </SessionContext.Provider>
    </ConfigContext.Provider>
  );
}

export default App;
