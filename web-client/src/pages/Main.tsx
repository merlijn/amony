import _ from "lodash";
import { useEffect, useState } from "react";
import { useLocation } from "react-router";
import { Constants } from "../api/Constants";
import { Prefs, Video } from "../api/Model";
import { useCookiePrefs, useListener, usePrevious, useStateNeq } from "../api/ReactUtils";
import Gallery, { MediaSelection } from "../components/Gallery";
import TopNavBar from "../components/navbar/TopNavBar";
import VideoModal from "../components/shared/VideoModal";
import './Main.scss';

const Main = () => {

    const location = useLocation();
    
    const [playVideo, setPlayVideo] = useState<Video | undefined>(undefined)
    const [showNavBar, setShowNavBar] = useState(true)
    const [prefs] = useCookiePrefs<Prefs>("prefs", "/", Constants.defaultPreferences)

    const getSelection = (): MediaSelection => {
      const urlParams = new URLSearchParams(location.search)  
      const q = urlParams.get("q")
      const d = urlParams.get("dir")

      return {
        query: q ? q : undefined,
        directory: d ? d : undefined,
        sortField: prefs.sortField,
        sortDirection: prefs.sortDirection,
        minimumQuality: prefs.minRes
      }
    }

    const [selection, setSelection] = useStateNeq<MediaSelection>(getSelection)

    useEffect(() => { setSelection(getSelection()) }, [location, prefs])

    const keyDownHandler = (event: KeyboardEvent) => {
      if (event.code === 'Slash')
        setShowNavBar(!showNavBar)
    }
  
    useListener('keydown', keyDownHandler)
  
    return (
        <>
          { playVideo && <VideoModal video={playVideo} onHide={() => setPlayVideo(undefined) } />}
          <div className="main-page">
            { showNavBar && <TopNavBar key="top-nav-bar" /> }
            <div style={ !showNavBar ?  { marginTop: 2 } : {} } key="main-gallery" className="main-gallery-container">
              <Gallery 
                selection = {selection}
                scroll = 'page' 
                onClick = { (v: Video) => setPlayVideo(v) } 
                columns = { prefs.gallery_columns }/>
            </div>
          </div>
        </>
      );
  }

  export default Main