import React, {useEffect, useState} from "react";
import {useNavigate, useLocation} from "react-router";
import {Constants, parseDurationParam, parseSortParam} from "../api/Constants";
import {MediaView, Prefs, Resource, ResourceSelection} from "../api/Model";
import {useCookiePrefs, useListener, useStateNeq} from "../api/ReactUtils";
import GridView from "../components/GridView";
import TopNavBar from "../components/navigation/TopNavBar";
import MediaModal from "../components/common/MediaModal";
import {isMobile} from "react-device-detect";
import './Main.scss';
import ListView from "../components/ListView";
import {buildUrl, copyParams} from "../api/Util";
import {Api} from "../api/Api";
import Modal from "../components/common/Modal";
import ConfigMenu from "../components/dialogs/ConfigMenu";

const Main = () => {
  
    const navigate = useNavigate();
    const location = useLocation();
    const [showMedia, setShowMedia] = useState<Resource | undefined>(undefined)
    const [showNavigation, setShowNavigation] = useState(true)
    const [view, setView] = useState<MediaView>('grid')
    const [showSettings,   setShowSettings]   = useState(false)
    const [prefs, updatePrefs] = useCookiePrefs<Prefs>("prefs-v1", "/", Constants.defaultPreferences)

    const getSelection = (): ResourceSelection => {
      const urlParams = new URLSearchParams(location.search)

      return {
        query: urlParams.get("q") || undefined,
        playlist: urlParams.get("playlist") || undefined,
        tag: urlParams.get("tag") || undefined,
        sort: parseSortParam(urlParams.get("s") || "date_added;desc"),
        duration: urlParams.has("d") ? parseDurationParam(urlParams.get("d") || "-") : undefined,
        minimumQuality: parseInt(urlParams.get("vq") || "0")
      }
    }

    const [selection, setSelection] = useStateNeq<ResourceSelection>(getSelection)

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
        navigate(buildUrl("/search", newParams));
    };

    const keyDownHandler = (event: KeyboardEvent) => {
      if (event.code === 'Slash') 
        setShowNavigation(!showNavigation)
    }
  
    useListener('keydown', keyDownHandler)
  
    const galleryStyle = { 
      marginTop: showNavigation ? 47 : 0,
      marginLeft: 0
    }

    const setShowSidebar = (value: boolean) => {
      updatePrefs({...prefs, showSidebar: !prefs.showSidebar})
    }

    return (
        <>
          { <MediaModal resource= { showMedia } onHide = { () => setShowMedia(undefined) } />}
            <Modal visible = { showSettings }   onHide = { () => setShowSettings(false) }>
                <ConfigMenu />
            </Modal>
          <div className="main-page">

            { showNavigation && 
                <TopNavBar 
                    key           = "top-nav-bar" 
                    onClickMenu   = { () => setShowSettings(true) }
                    activeView    = { view }
                    onViewChange  = { updateView }
                /> 
            }

            {
              (view === 'grid') &&
                  <GridView 
                    style     = { galleryStyle } 
                    className = "main-content-container"
                    key       = "gallery"
                    selection = { selection }
                    showTagbar = { showNavigation }
                    componentType = 'page'
                    onClick   = { (v: Resource) => setShowMedia(v) }
                    columns   = { prefs.gallery_columns }
                    previewOptionsFn = { (v: Resource) => {
                        return {
                          showPreviewOnHover: !isMobile,
                          showInfoBar: prefs.showTitles,
                          showDates: prefs.showDates,
                          showDuration: prefs.showDuration,
                          showResolution: prefs.showResolution,
                          showMenu: Api.session().isAdmin()
                        } 
                      }
                    }/>
            }

            {
              (view === 'list') &&
                <div style = { galleryStyle } key="main-content" className="main-content-container">
                  <ListView 
                    key       = "list"
                    onClick   = { (v: Resource) => setShowMedia(v) }
                    selection = {selection}
                   />
                </div>
            }
          </div>
        </>
      );
  }

  export default Main