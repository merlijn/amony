import React, {CSSProperties, useEffect, useRef, useState} from 'react';
import {Api} from '../api/Api';
import {defaultPrefs, Prefs, SearchResult, Video} from '../api/Model';
import Preview from './Preview';
import './Gallery.scss';
import {useLocation} from 'react-router-dom'
import {BoundedRatioBox, useCookiePrefs, useWindowSize} from "../api/Util";
import TopNavBar from "./TopNavBar";
import Plyr from "plyr";

const gridSize = 350
const gridReRenderThreshold = 24

const Gallery = (props: { cols?: number}) => {

  const location = useLocation();
  const initialSearchResult = new SearchResult(0,[]);

  const [searchResult, setSearchResult] = useState(initialSearchResult)

  const [prefs, setPrefs] = useCookiePrefs<Prefs>("prefs", "/", defaultPrefs)

  const windowSize = useWindowSize(((oldSize, newSize) => Math.abs(newSize.width - oldSize.width) > gridReRenderThreshold));

  const [playVideo, setPlayVideo] = useState<Video | undefined>(undefined)
  const videoElement = useRef<HTMLVideoElement>(null)

  // grid size
  const [ncols, setNcols] = useState(prefs.gallery_columns)

  // https://medium.com/geographit/accessing-react-state-in-event-listeners-with-usestate-and-useref-hooks-8cceee73c559
  // https://stackoverflow.com/questions/55265255/react-usestate-hook-event-handler-using-initial-state
  const [showNavBar, _setShowNavBar] = useState(true)
  const showNavBarRef = React.useRef(showNavBar);
  const setShowNavBar = (v: boolean) => {
    showNavBarRef.current = v;
    _setShowNavBar(v);
  };

  const urlParams = new URLSearchParams(location.search)

  const fetchData = (previous: Array<Video>) => {

    const offset = previous.length
    const n      = ncols * 5

    if (n > 0)
      Api.getVideos(
        urlParams.get("q") || "",
        urlParams.get("c"),
        n,
        offset).then(response => {

        const newvideos = (response as SearchResult).videos
        const videos = [...previous, ...newvideos]

        console.log("after fetch")
        console.log(`ncols: ${ncols}`)
        console.log(`prev : ${searchResult.videos.length}`)
        console.log(`extra: ${newvideos.length}`)
        console.log(`total: ${videos.length}`)

        setSearchResult({...response, videos: videos});
      });
  }

  const handleScroll = () => {

    if (Math.ceil(window.innerHeight + document.documentElement.scrollTop) === document.documentElement.offsetHeight) {
      fetchData(searchResult.videos)
    }
  }

  useEffect( () => {
    window.addEventListener('keydown', (event: KeyboardEvent) => {
      if (event.code === 'Slash') {
        setShowNavBar(!showNavBarRef.current)
      }
    })
  }, [])

  // infinite scroll
  useEffect(() => {
    const handler = () => handleScroll()
    window.addEventListener('scroll', handler);
    return () => window.removeEventListener('scroll', handler)
  }, [searchResult, ncols]);

  // useEffect(() => { fetchData() }, [location, ncols] )

  useEffect(() => {
    // clear the page
    // if (searchResult.videos.length > 0) {
      console.log('clearing results')
      fetchData([])
    // }
    // fetchData()
  }, [location, ncols])

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

  // show modal video player
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

      {showNavBar && <TopNavBar key="top-nav-bar" /> }

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
