import { useEffect, useState } from "react";
import 'react-pro-sidebar/dist/css/styles.css';
import { useLocation } from "react-router";
import { Constants } from "../api/Constants";
import { Prefs, Video } from "../api/Model";
import { useCookiePrefs, useListener, useStateNeq } from "../api/ReactUtils";
import Gallery, { MediaSelection } from "../components/Gallery";
import TopNavBar from "../components/navigation/TopNavBar";
import VideoModal from "../components/shared/VideoModal";
import SideBar from "../components/navigation/SideBar";
import { isMobile } from "react-device-detect";
import './Main.scss';

type SideBarState = 'hidden' | 'collapsed' | 'full'

const Main = () => {

    const location = useLocation();
    const [playVideo, setPlayVideo] = useState<Video | undefined>(undefined)
    const [showNavBar, setShowNavBar] = useState(true)
    const [prefs] = useCookiePrefs<Prefs>("prefs", "/", Constants.defaultPreferences)
    const [sidebarState, setSideBarState] = useState<SideBarState>('collapsed')

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

    const sideBar = <SideBar collapsed={sidebarState === 'collapsed'} onHide={() => {setSideBarState('hidden')}} />
  
    const galleryStyle = { 
      marginTop: showNavBar ? (isMobile ? 45 : 49) : 2,
      marginLeft: (sidebarState === 'hidden') ? 0 : 50
    }

    return (
        <>
          { playVideo && <VideoModal video={playVideo} onHide={() => setPlayVideo(undefined) } />}
          <div className="main-page">

            { (sidebarState !== 'hidden') && sideBar }
            { showNavBar && <TopNavBar key="top-nav-bar" onClickMenu = { () => setSideBarState('collapsed') } /> }

            <div style={ galleryStyle } key="main-gallery" className="main-gallery-container">
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