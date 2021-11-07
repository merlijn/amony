import React, {useEffect, useState} from 'react';
import Container from 'react-bootstrap/Container';
import Gallery, { GalleryProps } from './components/Gallery';
import Editor from './components/fragments/Editor';
import { Route,  BrowserRouter, useParams, Switch, useLocation } from 'react-router-dom';
import {Api} from "./api/Api";
import FragmentsPlayer from "./components/shared/FragmentsPlayer";
import {Fragment, SearchResult} from "./api/Model";

function App() {

  return (
    <Container className="amony-root" fluid>

      <BrowserRouter>

        <div>
          <Switch>
            <Route exact path="/" component = { GalleryRouter }  />
            <Route path="/search"  component = { GalleryRouter } />
            <Route path="/editor/:id" children = { <EditorRouter /> } />
            <Route exact path="/playfrags" children ={ <PlayFragments /> } />
          </Switch>
        </div>

      </BrowserRouter>

    </Container>
  );
}

const GalleryRouter = () => {

  const location = useLocation();
  
  const [galleryProps, setGalleryProps] = useState<GalleryProps>({})

  useEffect(() => { 
    
    const urlParams = new URLSearchParams(location.search)  
    const q = urlParams.get("q")
    const d = urlParams.get("dir")

    setGalleryProps({ query: q ? q : undefined, directory: d ? d : undefined})

  }, [location])

  return <Gallery {...galleryProps} />
}

const PlayFragments = () => {

  const [fragments, setFragments] = useState<Array<Fragment>>([])

  useEffect(() => {

      Api.getVideos("", 24, 0).then(response => {
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

function EditorRouter() {
  let { id } = useParams<{ id: string }>();
  return (
    <Editor videoId={id} />
  );
}

export default App;
