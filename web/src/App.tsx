import React from 'react';
import Container from 'react-bootstrap/Container';
import Gallery from './components/Gallery';
import Player from './components/player/Player';
import { Route,  BrowserRouter, useParams, Switch } from 'react-router-dom';

function App() {

  return (
    <Container className="root" fluid>

      <BrowserRouter>

        <div>
          <Switch>
            <Route exact path="/" component = { Gallery }  />
            <Route path="/search"  component = { Gallery } />
            <Route path="/video/:id" children = { <VideoRender /> } />
          </Switch>
        </div>

      </BrowserRouter>

    </Container>
  );
}

function VideoRender() {
  // We can use the `useParams` hook here to access
  // the dynamic pieces of the URL.
  let { id } = useParams<{ id: string }>();

  return (
    <Player videoId={id} />
  );
}

export default App;
