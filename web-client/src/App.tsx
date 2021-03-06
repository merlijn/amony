import { BrowserRouter, Route, Switch, useParams } from 'react-router-dom';
import Editor from './pages/Editor';
import Compilation from './pages/Compilation';
import Main from './pages/Main';
import VideoWall from './pages/VideoWall';

function App() {

  return (
    <div className="app-root">
      <BrowserRouter>
          <Switch>
            <Route exact path="/" component = { Main }  />
            <Route path="/search"  component = { Main } />
            <Route path="/editor/:id" children = { <EditorRouter /> } />
            <Route exact path="/video-wall" component = { VideoWall } />
            <Route exact path="/compilation" children ={ <Compilation /> } />
          </Switch>
      </BrowserRouter>

    </div>
  );
}

function EditorRouter() {
  let { id } = useParams<{ id: string }>();
  return (
    <Editor videoId={id} />
  );
}

export default App;
