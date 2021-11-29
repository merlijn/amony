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

const Main = () => {

    const location = useLocation();
    const [playVideo, setPlayVideo] = useState<Video | undefined>(undefined)
    const [showNavigation, setShowNavigation] = useState(true)
    const [showTagBar, setShowTagBar] = useState(true)
    const [showSideBar, setShowSideBar] = useState<Boolean>(false)

    const [prefs] = useCookiePrefs<Prefs>("prefs", "/", Constants.defaultPreferences)

    const getSelection = (): MediaSelection => {
      const urlParams = new URLSearchParams(location.search)  
      const q = urlParams.get("q")
      const d = urlParams.get("dir")

      return {
        query: urlParams.get("q") || undefined,
        directory: urlParams.get("dir") || undefined,
        tag: urlParams.get("tag") || undefined,
        sort: prefs.sort,
        minimumQuality: prefs.videoQuality
      }
    }

    const [selection, setSelection] = useStateNeq<MediaSelection>(getSelection)

    useEffect(() => { setSelection(getSelection()) }, [location, prefs])

    const keyDownHandler = (event: KeyboardEvent) => {
      if (event.code === 'Slash') 
        setShowNavigation(!showNavigation)
    }
  
    useListener('keydown', keyDownHandler)

    const calcTopMargin = () => {
      
      let m = 2;

      if (showNavigation)
        m += 49;
      if (showNavigation && showTagBar)
        m += 44
      if (isMobile)  
        m -= 4

      return m;
    }
  
    const galleryStyle = { 
      marginTop: calcTopMargin(),
      marginLeft: showNavigation && showSideBar ? 50 : 0
    }

    return (
        <>
          { playVideo && <VideoModal video={playVideo} onHide={() => setPlayVideo(undefined) } />}
          <div className="main-page">

            { showNavigation && showSideBar && <SideBar collapsed={true} onHide={() => {setShowSideBar(false)}} /> }
            { showNavigation && <TopNavBar key="top-nav-bar" showTagsBar = {showTagBar} onShowTagsBar = { (show) => setShowTagBar(show) } onClickMenu = { () => setShowSideBar(true) } /> }

            <div style={ galleryStyle } key="main-gallery" className="main-gallery-container">
              <Gallery 
                selection = {selection}
                scroll = 'page' 
                onClick = { (v: Video) => setPlayVideo(v) } 
                columns = { prefs.gallery_columns }
                previewOptionsFn = { (v: Video) => {
                    return {
                      showPreviewOnHover: !isMobile,
                      showInfoBar: prefs.showTitles,
                      showDates: prefs.showDates,
                      showDuration: prefs.showDuration,
                      showMenu: prefs.showMenu
                    } 
                  }
                }/>
            </div>
          </div>
        </>
      );
  }

  export default Main