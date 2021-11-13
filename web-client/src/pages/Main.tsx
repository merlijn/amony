import { useEffect, useState } from "react";
import { useLocation } from "react-router";
import { Constants } from "../api/Constants";
import { Columns, Prefs, Video } from "../api/Model";
import { useCookiePrefs, useListener, usePrevious } from "../api/Util";
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

    const [selection, setSelection] = useState<MediaSelection>(getSelection)
    const previousSelection = usePrevious(selection)

    useEffect(() => { 
      
        setSelection(getSelection())

      // if (previousPrefs?.minRes !== prefs.minRes ||
      //   previousPrefs?.sortField !== prefs.sortField ||
      //   previousPrefs?.sortDirection !== prefs.sortDirection)
        
  
    }, [location, prefs])

    const keyDownHandler = (event: KeyboardEvent) => {
      if (event.code === 'Slash')
        setShowNavBar(!showNavBar)
    }

    const urlParams = new URLSearchParams(location.search)  
    const q = urlParams.get("q")
    const d = urlParams.get("dir")


  
    useListener('keydown', keyDownHandler)
  
    return (
        <>
          <div className="main-page">
          { playVideo && <VideoModal video={playVideo} onHide={() => setPlayVideo(undefined) } />}
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