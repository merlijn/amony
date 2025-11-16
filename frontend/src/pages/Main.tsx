import React, {useEffect, useState} from "react";
import {useLocation, useNavigate} from "react-router";
import {Constants, parseDurationParam, parseSortParam} from "../api/Constants";
import {MediaView, ResourceSelection} from "../api/Model";
import {useListener, useStateNeq} from "../api/ReactUtils";
import GridView from "../components/GridView";
import TopNavBar from "../components/navigation/TopNavBar";
import ResourceViewModal from "../components/common/ResourceViewModal";
import {isMobile} from "react-device-detect";
import './Main.scss';
import ListView from "../components/ListView";
import {buildUrl, copyParams} from "../api/Util";
import Modal from "../components/common/Modal";
import ConfigMenu from "../components/dialogs/ConfigMenu";
import {ResourceDto} from "../api/generated";
import {useLocalStorage} from "usehooks-ts";

const Main = () => {
  
    const navigate = useNavigate();
    const location = useLocation();
    const [showResource, setShowResource] = useState<ResourceDto | undefined>(undefined)
    const [showNavigation, setShowNavigation] = useState(true)
    const [view, setView] = useState<MediaView>('grid')
    const [showSettings,   setShowSettings]   = useState(false)
    // const [prefs, updatePrefs] = useLocalStoragePrefs<Prefs>(Constants.preferenceKey, Constants.defaultPreferences)
    const [prefs, updatePrefs, removeValue] = useLocalStorage(Constants.preferenceKey, Constants.defaultPreferences)

    const getSelection = (): ResourceSelection => {
      const urlParams = new URLSearchParams(location.search)

      const untagged = (urlParams.get("untagged") || "").toLowerCase() === "true"

      return {
        query: urlParams.get("q") || undefined,
        playlist: urlParams.get("playlist") || undefined,
        tag: untagged ? undefined : urlParams.get("tag") || undefined,
        untagged: untagged || undefined,
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
      display: 'relative',
      paddingTop: showNavigation ? 47 : 0,
      marginLeft: 0
    }

    const setShowSidebar = (value: boolean) => {
      updatePrefs({...prefs, showSidebar: !prefs.showSidebar})
    }

    return (
        <>
          <ResourceViewModal resource= { showResource } onHide = { () => setShowResource(undefined) } />
          <Modal visible = { showSettings } onHide = { () => setShowSettings(false) }>
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
                  onClick   = { (v: ResourceDto) => setShowResource(v) }
                  columns   = { prefs.gallery_columns }
                  previewOptionsFn = { (v: ResourceDto) => {
                      return {
                        showPreviewOnHover: !isMobile,
                        showInfoBar: prefs.showTitles,
                        showDates: prefs.showDates,
                        showDuration: prefs.showDuration,
                        showResolution: prefs.showResolution
                      }
                    }
                  }/>
            }

            {
              (view === 'list') &&
                <div style = { galleryStyle } key="main-content" className="main-content-container">
                  <ListView 
                    key       = "list"
                    onClick   = { (v: ResourceDto) => setShowResource(v) }
                    selection = {selection}
                   />
                </div>
            }
          </div>
        </>
      );
  }

  export default Main
