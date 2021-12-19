import { useEffect, useState } from "react";
import 'react-pro-sidebar/dist/css/styles.css';
import { useHistory, useLocation } from "react-router";
import { Constants } from "../api/Constants";
import { MediaSelection, MediaView, Prefs, Video } from "../api/Model";
import { useCookiePrefs, useListener, useStateNeq } from "../api/ReactUtils";
import Gallery from "../components/Gallery";
import TopNavBar from "../components/navigation/TopNavBar";
import VideoModal from "../components/shared/VideoModal";
import SideBar from "../components/navigation/SideBar";
import { isMobile } from "react-device-detect";
import './Main.scss';
import ListView from "../components/ListView";
import { buildUrl, copyParams } from "../api/Util";

const Main = () => {
  
    const history = useHistory();
    const location = useLocation();
    const [playVideo, setPlayVideo] = useState<Video | undefined>(undefined)
    const [showNavigation, setShowNavigation] = useState(true)
    const [showTagBar] = useState(true)
    const [view, setView] = useState<MediaView>('grid')

    const [prefs, updatePrefs] = useCookiePrefs<Prefs>("prefs/v1", "/", Constants.defaultPreferences)

    const getSelection = (): MediaSelection => {
      const urlParams = new URLSearchParams(location.search)

      return {
        query: urlParams.get("q") || undefined,
        playlist: urlParams.get("playlist") || undefined,
        tag: urlParams.get("tag") || undefined,
        sort: prefs.sort,
        minimumQuality: prefs.videoQuality
      }
    }

    const [selection, setSelection] = useStateNeq<MediaSelection>(getSelection)

    useEffect(() => { 

      const urlParams = new URLSearchParams(location.search)

      let viewParam: MediaView = view

      if (urlParams.get("view") === "list" && !isMobile)
        viewParam = "list"
      else if (urlParams.get("view") === "grid")
        viewParam = "grid"
        
      if (viewParam !== view)
        setView(viewParam)

      setSelection(getSelection()) 
    }, [location, prefs])

    const updateView = (e: MediaView) => {
      const params = new URLSearchParams(location.search)
      const newParams = copyParams(params)
      newParams.set("view", e)
      history.push(buildUrl("/search", newParams));
    };

    const keyDownHandler = (event: KeyboardEvent) => {
      if (event.code === 'Slash') 
        setShowNavigation(!showNavigation)
    }
  
    useListener('keydown', keyDownHandler)

    const calcTopMargin = () => {
      
      let m = 2;

      if (showNavigation)
        m += 45;

      return m;
    }
  
    const galleryStyle = { 
      marginTop: calcTopMargin(),
      marginLeft: showNavigation && prefs.showSidebar ? 50 : 0
    }

    const setShowSidebar = (value: boolean) => {
      updatePrefs({...prefs, showSidebar: !prefs.showSidebar})
    }

    return (
        <>
          { <VideoModal video = { playVideo } onHide={() => setPlayVideo(undefined) } />}
          
          <div className="main-page">

            { showNavigation && prefs.showSidebar && 
                <SideBar 
                  collapsed = { true } 
                  onHide    = { () => setShowSidebar(!prefs.showSidebar) } 
                /> 
            }
            
            { showNavigation && 
                <TopNavBar 
                    key           = "top-nav-bar" 
                    showTagsBar   = { showTagBar && view !== "list" } 
                    onClickMenu   = { () => setShowSidebar(true) } 
                    activeView    = { view }
                    // playList      = "nature"
                    onViewChange  = { updateView }
                /> 
            }

            {
              (view === 'grid') &&
                <div style = { galleryStyle } key="main-content" className="main-content-container">
                  <Gallery 
                    key       = "gallery"
                    selection = { selection }
                    scroll    = 'page' 
                    onClick   = { (v: Video) => setPlayVideo(v) } 
                    columns   = { prefs.gallery_columns }
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
            }

            {
              (view === 'list') &&
                <div style = { galleryStyle } key="main-content" className="main-content-container">
                  <ListView 
                    key       = "list"
                    onClick   = { (v: Video) => setPlayVideo(v) } 
                    selection = {selection}
                   />
                </div>
            }
          </div>
        </>
      );
  }

  export default Main