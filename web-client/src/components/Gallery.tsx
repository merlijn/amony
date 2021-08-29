import React, {CSSProperties, useEffect, useRef, useState} from 'react';
import {Api} from '../api/Api';
import {defaultPrefs, Prefs, SearchResult, Video} from '../api/Model';
import Preview from './Preview';
import './Gallery.scss';
import {useLocation} from 'react-router-dom'
import {BoundedRatioBox, useCookiePrefs, usePrevious, useWindowSize} from "../api/Util";
import TopNavBar from "./TopNavBar";
import Plyr from "plyr";

const gridSize = 350
const gridReRenderThreshold = 24
const fetchDataScreenMargin = 1024;

const Gallery = () => {

  const location = useLocation();
  const initialSearchResult = new SearchResult(0,[]);

  const [prefs, setPrefs] = useCookiePrefs<Prefs>("prefs", "/", defaultPrefs)

  const previousPrefs = usePrevious(prefs)

  const [minRes, setMinRes] = useState(prefs.minRes)
  const [searchResult, setSearchResult] = useState(initialSearchResult)
  const [isFetching, setIsFetching] = useState(false)

  const [playVideo, setPlayVideo] = useState<Video | undefined>(undefined)

  const windowSize = useWindowSize(((oldSize, newSize) => Math.abs(newSize.width - oldSize.width) > gridReRenderThreshold));
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
    const n      = ncols * 12

    if (n > 0)
      Api.getVideos(
        urlParams.get("q") || "",
        urlParams.get("tag"),
        n,
        offset,
        prefs.minRes).then(response => {

        const newvideos = (response as SearchResult).videos
        const videos = [...previous, ...newvideos]

        setIsFetching(false);
        setSearchResult({...response, videos: videos});
      });
  }

  const handleScroll = () => {

    console.log(`offsetheight: ${document.documentElement.offsetHeight}`)
    console.log(`scrolltop   : ${document.documentElement.scrollTop}`)
    console.log(`innerheight : ${window.innerHeight}`)

    const withinFetchMargin = document.documentElement.offsetHeight - Math.ceil(window.innerHeight + document.documentElement.scrollTop) <=  fetchDataScreenMargin

    if (withinFetchMargin && !isFetching) {
      console.log("setting isFetching=true")
      setIsFetching(true)
    }


    // const diff = Math.ceil(window.innerHeight + document.documentElement.scrollTop) === document.documentElement.offsetHeight

    // if (Math.ceil(window.innerHeight + document.documentElement.scrollTop) === document.documentElement.offsetHeight) {
    //   fetchData(searchResult.videos)
    // }
  }

  // add keyboard listener
  useEffect( () => {
    window.addEventListener('keydown', (event: KeyboardEvent) => {
      if (event.code === 'Slash') {
        setShowNavBar(!showNavBarRef.current)
      }
    })
  }, [])

  // add scroll listener
  useEffect(() => {
    const handler = () => handleScroll()
    window.addEventListener('scroll', handler);
    return () => window.removeEventListener('scroll', handler)
  }, [searchResult, ncols]);


  useEffect(() => { fetchData([]) }, [location])
  useEffect(() => { fetchData(searchResult.videos) }, [ncols])

  useEffect(() => {
    if (isFetching) {
      fetchData(searchResult.videos);
    }
  }, [isFetching]);

  useEffect(() => {

    if (prefs.gallery_columns === 0) {
      const c = Math.min(Math.max(2, Math.round(windowSize.width / gridSize)), 5);
      if (c !== ncols)
        setNcols(c)
    } else if (prefs.gallery_columns != ncols) {
      setNcols(prefs.gallery_columns)
    }

    if (previousPrefs?.minRes !== prefs.minRes)
      fetchData([])

  },[windowSize, prefs]);

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
              showPreviewOnHover={true}
              showTitles={prefs.showTitles} showDuration={prefs.showDuration} showMenu={prefs.showMenu}/>
  })

  const modalSize = (v:Video | undefined): CSSProperties => {
     return v ? BoundedRatioBox("75vw", "75vh", v.resolution_x / v.resolution_y) : { }
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