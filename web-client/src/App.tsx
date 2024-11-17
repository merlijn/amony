import {BrowserRouter, Route, Routes, useParams} from 'react-router-dom';
import Editor from './pages/Editor';
import Compilation from './pages/Compilation';
import Main from './pages/Main';
import VideoWall from './pages/VideoWall';

function App() {

  return (
    <div className="app-root">
      <BrowserRouter>
          <Routes>
            <Route path="/" element = { <Main /> }  />
            <Route path="/search"  element = { <Main /> } />
            <Route path="/editor/:id" element = { <EditorRouter /> } />
            <Route path="/video-wall" element = { <VideoWall /> } />
            <Route path="/compilation" element = { <Compilation /> } />
          </Routes>
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
