import {BrowserRouter, Route, Routes, useParams} from 'react-router-dom';
import React, {lazy, Suspense, useEffect} from 'react';
import {Constants, SessionContext} from "./api/Constants";
import {SessionInfo} from "./api/Api";
import {getSession} from "./api/generated";
import {AxiosError} from "axios";

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
    }).catch((error: AxiosError) => {
      if (error.response?.status === 401) {
        setSession(Constants.anonymousSession);
        return;
      }
      console.log("Error getting session", e);
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
