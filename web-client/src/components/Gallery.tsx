import React, {CSSProperties, useEffect, useRef, useState} from 'react';
import {Api} from '../api/Api';
import {Prefs, SearchResult, Video} from '../api/Model';
import Preview from './Preview';
import './Gallery.scss';
import {useLocation} from 'react-router-dom'
import {
  BoundedRatioBox,
  calculateColumns,
  useCookiePrefs,
  useListener,
  usePrevious,
  useStateRef,
  useWindowSize
} from "../api/Util";
import TopNavBar from "./navbar/TopNavBar";
import Plyr from "plyr";
import { isMobile } from "react-device-detect";
import {Constants} from "../api/Constants";

const gridReRenderThreshold = 24
const fetchDataScreenMargin = 1024;
const navBarHeight = 45

const Gallery = () => {

  const location = useLocation();
  const initialSearchResult = new SearchResult(0,[]);

  const [prefs, setPrefs] = useCookiePrefs<Prefs>("prefs", "/", Constants.defaultPreferences)

  // https://medium.com/geographit/accessing-react-state-in-event-listeners-with-usestate-and-useref-hooks-8cceee73c559
  // https://stackoverflow.com/questions/55265255/react-usestate-hook-event-handler-using-initial-state
  const [showNavBar, setShowNavBar] = useState(true)

  const previousPrefs = usePrevious(prefs)

  const [searchResult, setSearchResult] = useState(initialSearchResult)
  const [isFetching, setIsFetching] = useState(false)

  const [playVideo, setPlayVideo] = useState<Video | undefined>(undefined)

  const windowSize = useWindowSize(((oldSize, newSize) => Math.abs(newSize.width - oldSize.width) > gridReRenderThreshold));
  const videoElement = useRef<HTMLVideoElement>(null)

  // grid size
  const [ncols, setNcols] = useState(prefs.gallery_columns)
  const urlParams = new URLSearchParams(location.search)

  const fetchData = (previous: Array<Video>) => {

    const offset = previous.length
    const n      = ncols * 12

    if (n > 0)
      Api.getVideos(
        urlParams.get("q") || "",
        urlParams.get("dir"),
        n,
        offset,
        prefs.minRes,
        prefs.sortField,
        prefs.sortDirection).then(response => {

        const newvideos = (response as SearchResult).videos
        const videos = [...previous, ...newvideos]

        setIsFetching(false);
        setSearchResult({...response, videos: videos});
      });
  }

  const handleScroll = (e: Event) => {

    const withinFetchMargin = document.documentElement.offsetHeight - Math.ceil(window.innerHeight + document.documentElement.scrollTop) <=  fetchDataScreenMargin

    if (withinFetchMargin && !isFetching) {
      setIsFetching(true)
    }
  }

  const keyDownHandler = (event: KeyboardEvent) => {
    console.log(`keycode: ${event.code}`)
    if (event.code === 'Slash')
      setShowNavBar(!showNavBar)
    // else if (event.code === 'KeyI')
    //   setPrefs({...prefs, showTitles: !prefs.showTitles })
    // else if (event.code === 'KeyM')
    //   setPrefs( {...prefs, showMenu: !prefs.showMenu})
    // else if (event.code === 'KeyD')
    //   setPrefs({...prefs, showDuration: !prefs.showDuration})
  }

  useListener('keydown', keyDownHandler, [prefs])
  useListener('scroll', handleScroll, [searchResult, ncols])

  useEffect(() => { fetchData([]) }, [location])
  useEffect(() => { fetchData(searchResult.videos) }, [ncols])

  useEffect(() => {
    if (isFetching) {
      fetchData(searchResult.videos);
    }
  }, [isFetching]);

  useEffect(() => {

    if (prefs.gallery_columns === 0) {
      const c = calculateColumns();
      if (c !== ncols)
        setNcols(c)
    } else if (prefs.gallery_columns != ncols) {
      setNcols(prefs.gallery_columns)
    }

    if (previousPrefs?.minRes !== prefs.minRes ||
        previousPrefs?.sortField !== prefs.sortField ||
        previousPrefs?.sortDirection !== prefs.sortDirection)
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

    const style: { } = { "--ncols" : `${ncols}` }

    return <Preview
      style={style} className="grid-cell"
      key={`preview-${vid.id}`}
      vid={vid}
      onClick={ (v) => setPlayVideo(v) }
      showPreviewOnHover={!isMobile}
      showInfoBar={prefs.showTitles} showDates = {true} showDuration={prefs.showDuration} showMenu={prefs.showMenu}/>
  })

  const modalSize = (v: Video | undefined): CSSProperties => {

     const w = isMobile ? "100vw" : "75vw"

     return v ? BoundedRatioBox(w, "75vh", v.resolution_x / v.resolution_y) : { }
  }

  return (
    <div className="gallery-container full-width">

      {showNavBar && <TopNavBar key="top-nav-bar" /> }

      <div style={ !showNavBar ?  { marginTop: 2 } : {} } key="gallery" className="gallery">
        <div
          key="gallery-video-player"
          className="video-modal-container"
          style={ playVideo === undefined ? { display: "none"}: {display: "block" }}>

          <div key="video-model-background"
               className="video-modal-background"
               onClick = { (e) => setPlayVideo(undefined) }></div>

          <div key="video-model-content" className="video-modal-content">
            {
               <div style={modalSize(playVideo)}>
                  <video ref={videoElement} id="gallery-video-player"  playsInline controls>
                    { playVideo && <source src={'/files/videos/' + playVideo.id} type="video/mp4"/> }
                  </video>
               </div>
            }
          </div>
        </div>
        <div className="gallery-grid-container">
           {previews}
        </div>
      </div>
    </div>
  );
}

export default Gallery;
