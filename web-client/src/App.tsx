import Container from 'react-bootstrap/Container';
import { BrowserRouter, Route, Switch, useParams } from 'react-router-dom';
import Editor from './components/fragments/Editor';
import Compilation from './pages/Compilation';
import MainGallery from './pages/MainGallery';

function App() {

  return (
    <Container className="amony-root" fluid>

      <BrowserRouter>

        <div>
          <Switch>
            <Route exact path="/" component = { MainGallery }  />
            <Route path="/search"  component = { MainGallery } />
            <Route path="/editor/:id" children = { <EditorRouter /> } />
            <Route exact path="/playfrags" children ={ <Compilation /> } />
          </Switch>
        </div>

      </BrowserRouter>

    </Container>
  );
}

function EditorRouter() {
  let { id } = useParams<{ id: string }>();
  return (
    <Editor videoId={id} />
  );
}

export default App;
