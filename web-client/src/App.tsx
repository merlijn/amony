import React, {useEffect, useState} from 'react';
import Container from 'react-bootstrap/Container';
import Gallery from './components/Gallery';
import Editor from './components/fragments/Editor';
import { Route,  BrowserRouter, useParams, Switch } from 'react-router-dom';
import {Api} from "./api/Api";
import FragmentsPlayer from "./components/shared/FragmentsPlayer";
import {Fragment, SearchResult} from "./api/Model";

function App() {

  return (
    <Container className="root" fluid>

      <BrowserRouter>

        <div>
          <Switch>
            <Route exact path="/" component = { Gallery }  />
            <Route path="/search"  component = { Gallery } />
            <Route path="/editor/:id" children = { <VideoRender /> } />
            <Route exact path="/playfrags" children ={ <PlayFragments /> } />
          </Switch>
        </div>

      </BrowserRouter>

    </Container>
  );
}

const PlayFragments = () => {

  const [fragments, setFragments] = useState<Array<Fragment>>([])

  useEffect(() => {

      Api.getVideos(
        "",
        null,
        24,
        0).then(response => {
          const f = response as SearchResult
          const frags = f.videos.flatMap((v) => { return v.fragments })
          setFragments(frags)
      });
    }, []
  )

  if (fragments.length > 0) {
    return <FragmentsPlayer id="all-fragments"
      style={ { width: 800, height: 500 } }
      className="abs-center"
      fragments={fragments} />
  } else
    return <div />
}

function VideoRender() {
  let { id } = useParams<{ id: string }>();
  return (
    <Editor videoId={id} />
  );
}

export default App;
