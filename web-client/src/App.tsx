import {BrowserRouter, Route, Routes, useParams} from 'react-router-dom';
import React, {Suspense, lazy, useEffect} from 'react';
import {QueryClient, QueryClientProvider} from "@tanstack/react-query";
import { createSyncStoragePersister } from '@tanstack/query-sync-storage-persister'
import { persistQueryClient } from '@tanstack/react-query-persist-client'
import {Constants, SessionContext} from "./api/Constants";
import {Api, SessionInfo} from "./api/Api";
import {getSession} from "./api/generated";

const Editor = lazy(() => import('./pages/Editor'));
const Compilation = lazy(() => import('./pages/Compilation'));
const Main = lazy(() => import('./pages/Main'));
const VideoWall = lazy(() => import('./pages/VideoWall'));

function App() {

  const [session, setSession] = React.useState<SessionInfo | null>(null);

  useEffect(() => {

    getSession().then((authToken) => {
      const sessionInfo: SessionInfo = {
        isLoggedIn()   { return true },
        isAdmin: ()=> authToken.roles.includes("admin")
      }
      setSession(sessionInfo);
    });
  }, []);

  return (

      <div className="app-root">
        {
          !session ? <div /> :
            <BrowserRouter>
              <Suspense fallback = { <div /> }>
                <SessionContext.Provider value = { session }>
                  <Routes>
                    <Route path="/" element={<Main />} />
                    <Route path="/search" element={<Main />} />
                    <Route path="/editor/:id" element={<EditorRouter />} />
                    <Route path="/video-wall" element={<VideoWall />} />
                    <Route path="/compilation" element={<Compilation />} />
                  </Routes>
                </SessionContext.Provider>
                </Suspense>
            </BrowserRouter>
        }
      </div>

  );
}

function EditorRouter() {
  let { id } = useParams<{ id: string }>();
  return (
      <>
          { id && <Editor videoId = { id } /> }
      </>
  );
}

export default App;
