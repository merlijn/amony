import React, {CSSProperties, useEffect, useRef, useState} from 'react';
import {Api} from '../api/Api';
import {defaultPrefs, Prefs, SearchResult, Video} from '../api/Model';
import Preview from './Preview';
import './Gallery.scss';
import {useLocation} from 'react-router-dom'
import {BoundedRatioBox, useCookiePrefs, useWindowSize} from "../api/Util";
import TopNavBar from "./TopNavBar";
import {pageSizes} from "../api/Constants";
import {Modal} from "react-bootstrap";
import Button from "react-bootstrap/Button";
import Plyr from "plyr";

const gridSize = 350
const gridReRenderThreshold = 24

const Gallery = (props: { cols?: number}) => {

  const location = useLocation();
  const [searchResult, setSearchResult] = useState(new SearchResult(0, 0, 0,[]))

  const [playVideo, setPlayVideo] = useState<Video | undefined>(undefined)
  const videoElement = useRef<HTMLVideoElement>(null)

  const [prefs, setPrefs] = useCookiePrefs<Prefs>("prefs", "/", defaultPrefs)

  // https://medium.com/geographit/accessing-react-state-in-event-listeners-with-usestate-and-useref-hooks-8cceee73c559
  // https://stackoverflow.com/questions/55265255/react-usestate-hook-event-handler-using-initial-state
  const [showNavBar, _setShowNavBar] = useState(true)
  const showNavBarRef = React.useRef(showNavBar);
  const setShowNavBar = (v: boolean) => {
    showNavBarRef.current = v;
    _setShowNavBar(v);
  };

  useEffect( () => {
    document.addEventListener('keydown', (event: KeyboardEvent) => {
      // ArrowLeft, ArrowRight
      console.log(`gallery: ${event.code}`)
      if (event.code === 'Slash') {
        setShowNavBar(!showNavBarRef.current)
      }
    })
  }, [])

  const urlParams = new URLSearchParams(location.search)
  const windowSize = useWindowSize(((oldSize, newSize) => Math.abs(newSize.width - oldSize.width) > gridReRenderThreshold));

  // grid size
  const [ncols, setNcols] = useState(prefs.gallery_columns)
  const pageSize = pageSizes.get(ncols) || 60

  const currentPage = () => parseInt(urlParams.get("p") || "1");

  useEffect(() => {

      const offset = (currentPage()-1) * pageSize

      Api.getVideos(
        urlParams.get("q") || "",
        urlParams.get("c"),
        pageSize,
        offset).then(response => { setSearchResult(response); });

    }, [location]
  )

  useEffect(() => {

    if (prefs.gallery_columns === 0) {
      const c = Math.min(Math.max(2, Math.round(windowSize.width / gridSize)), 5);
      if (c !== ncols)
        setNcols(c)
    } else if (prefs.gallery_columns != ncols) {
      setNcols(prefs.gallery_columns)
    }
  },[windowSize, prefs]);

  const previews = searchResult.videos.map((vid, idx) => {

    let style: { } = { "--ncols" : `${ncols}` }

    if (idx % ncols === 0)
        style = { ...style, paddingLeft : "4px" }
    else if ((idx + 1) % ncols === 0)
        style = { ...style, paddingRight : "4px" }

    return <Preview
              style={style} className="grid-cell"
              key={`preview-${vid.id}`}
              vid={vid}
              onClick={ (v) => setPlayVideo(v) }
              showTitles={prefs.showTitles} showDuration={prefs.showDuration} showMenu={prefs.showMenu}/>
  })

  useEffect(() => {

    const element = videoElement.current

    if (element) {
      if (playVideo !== undefined) {
        element.load()
        const plyr = new Plyr(element, { fullscreen : { enabled: true }, invertTime: false})
        plyr.play()
      }
      else {
        element.pause()
      }
    }
  },[playVideo]);

  const modalSize = (v:Video | undefined): CSSProperties => {
     return v ? BoundedRatioBox("70vw", "70vh", v.resolution_x / v.resolution_y) : { }
  }

  return (
    <div className="gallery-container full-width">

      {showNavBar && <TopNavBar key="top-nav-bar" currentPage = {currentPage() } lastPage={Math.ceil(searchResult.total / pageSize)} /> }

      <div style={{ marginTop: showNavBar ? 42 : 2 }} key="gallery" className="gallery">
        <div
          key="gallery-video-player"
          className="custom-modal-container"
          style={ playVideo === undefined ? { display: "none"}: {display: "block" }}>

          <div key="custom-model-background"
               className="custom-modal-background"
               onClick = { (e) => setPlayVideo(undefined) }></div>

          <div key="custom-model-content" className="custom-modal-content">
            {
               <div className="video-player" style={modalSize(playVideo)}>
                  <video ref={videoElement} id="gallery-video-player"  playsInline controls>
                    { playVideo && <source src={'/files/videos/' + playVideo.id} type="video/mp4"/> }
                  </video>
               </div>
            }
          </div>
        </div>
        {previews}
      </div>
    </div>
  );
}

export default Gallery;
