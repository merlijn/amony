import React, { useEffect, useState } from 'react';
import { isMobile } from "react-device-detect";
import { useLocation } from 'react-router-dom';
import { Api } from '../api/Api';
import { Constants } from "../api/Constants";
import { Prefs, SearchResult, Video } from '../api/Model';
import {
  calculateColumns,
  useCookiePrefs,
  useListener,
  usePrevious, useWindowSize
} from "../api/Util";
import './Gallery.scss';
import TopNavBar from "./navbar/TopNavBar";
import Preview from './Preview';
import VideoModal from "./shared/VideoModal";

const gridReRenderThreshold = 24
const fetchDataScreenMargin = 1024;

export type GalleryProps = {
  query?: string
  directory?: string
}

const Gallery = (props: GalleryProps) => {

  const initialSearchResult = new SearchResult(0,[]);

  const [prefs] = useCookiePrefs<Prefs>("prefs", "/", Constants.defaultPreferences)

  // https://medium.com/geographit/accessing-react-state-in-event-listeners-with-usestate-and-useref-hooks-8cceee73c559
  // https://stackoverflow.com/questions/55265255/react-usestate-hook-event-handler-using-initial-state
  const [showNavBar, setShowNavBar] = useState(true)

  const previousPrefs = usePrevious(prefs)

  const [searchResult, setSearchResult] = useState(initialSearchResult)
  const [isFetching, setIsFetching] = useState(false)

  const [playVideo, setPlayVideo] = useState<Video | undefined>(undefined)

  const windowSize = useWindowSize(((oldSize, newSize) => Math.abs(newSize.width - oldSize.width) > gridReRenderThreshold));

  // grid size
  const [ncols, setNcols] = useState(prefs.gallery_columns)

  const fetchData = (previous: Array<Video>) => {

    const offset = previous.length
    const n      = ncols * 12

    if (n > 0)
      Api.getVideos(
        props.query || "",
        n,
        offset,
        props.directory,
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
  }

  useListener('keydown', keyDownHandler, [prefs])
  useListener('scroll', handleScroll, [searchResult, ncols])

  useEffect(() => { fetchData([]) }, [props])
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

  return (
    <div className="gallery-container full-width">
      { showNavBar && <TopNavBar key="top-nav-bar" /> }
      { playVideo && <VideoModal video={playVideo} onHide={() => setPlayVideo(undefined) } />}

      <div style={ !showNavBar ?  { marginTop: 2 } : {} } key="gallery" className="gallery">

        <div className="gallery-grid-container">
          {previews}
        </div>
      </div>
    </div>
  );
}

export default Gallery;
