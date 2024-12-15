import {BrowserRouter, Route, Routes, useParams} from 'react-router-dom';
import React, { Suspense, lazy } from 'react';

const Editor = lazy(() => import('./pages/Editor'));
const Compilation = lazy(() => import('./pages/Compilation'));
const Main = lazy(() => import('./pages/Main'));
const VideoWall = lazy(() => import('./pages/VideoWall'));

function App() {

  return (
    <div className="app-root">
      <BrowserRouter>
        <Suspense fallback = { <div>Loading...</div> }>
          <Routes>
            <Route path="/" element={<Main />} />
            <Route path="/search" element={<Main />} />
            <Route path="/editor/:id" element={<EditorRouter />} />
            <Route path="/video-wall" element={<VideoWall />} />
            <Route path="/compilation" element={<Compilation />} />
          </Routes>
        </Suspense>
      </BrowserRouter>
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
