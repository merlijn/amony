import {BrowserRouter, Route, Routes, useParams} from 'react-router-dom';
import React, {lazy, Suspense, useMemo, use} from 'react';
import {Constants, SessionContext} from "./api/Constants";
import {getSession} from "./api/generated";
import {AxiosError} from "axios";
import {SessionInfo} from "./api/Model";
import {ThemeProvider} from "./ThemeContext";

const Editor = lazy(() => import('./pages/Editor'));
const Compilation = lazy(() => import('./pages/Compilation'));
const Main = lazy(() => import('./pages/Main'));
const VideoWall = lazy(() => import('./pages/VideoWall'));

function App() {
  const sessionPromise = useMemo(() =>
      getSession()
        .then((authToken) => ({
          isLoggedIn: () => true,
          isAdmin: () => authToken.roles.includes("admin")
        } as SessionInfo))
        .catch((error: AxiosError) => {
          if (error.response?.status === 401) {
            return Constants.anonymousSession;
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
            <SessionProvider sessionPromise={sessionPromise}>
              <Routes>
                <Route path="/" element={<Main />} />
                <Route path="/search" element={<Main />} />
                <Route path="/editor/:bucketId/:resourceId" element={<EditorRouter />} />
                <Route path="/video-wall" element={<VideoWall />} />
                <Route path="/compilation" element={<Compilation />} />
              </Routes>
            </SessionProvider>
          </Suspense>
        </BrowserRouter>
      </div>
    </ThemeProvider>
  );
}

function SessionProvider({ sessionPromise, children }: { sessionPromise: Promise<SessionInfo>, children: React.ReactNode }) {
  const session = use(sessionPromise);
  return <SessionContext.Provider value={session}>{children}</SessionContext.Provider>;
}

function EditorRouter() {
  let { bucketId, resourceId } = useParams<{ bucketId: string, resourceId: string }>();
  return (
    <>
      {(bucketId && resourceId) && <Editor bucketId={bucketId} resourceId={resourceId} />}
    </>
  );
}

export default App;
