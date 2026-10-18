import React, {useEffect, useState} from "react";
import {useLocation, useNavigate} from "react-router-dom";
import {parseResolutionParam, parseSortParam, parseThresholdRangeParam} from "../api/Constants";
import {MediaView, ResourceSelection} from "../api/Model";
import {useListener, useStateNeq} from "../api/ReactUtils";
import GridView from "../components/GridView";
import TopNavBar from "../components/navigation/TopNavBar";
import ResourceViewModal from "../components/common/ResourceViewModal";
import {isMobile} from "react-device-detect";
import '../components/common/Dialog.scss';
import './Main.scss';
import ListView from "../components/ListView";
import {buildUrl, copyParams, cssAspectRatio} from "../api/Util";
import * as Dialog from "@radix-ui/react-dialog";
import ConfigMenu from "../components/dialogs/ConfigMenu";
import {ResourceDto} from "../api/generated";
import {usePrefs} from "../api/Prefs";

const Main = () => {
  
    const navigate = useNavigate();
    const location = useLocation();
    const [showResource, setShowResource] = useState<ResourceDto | undefined>(undefined)
    const [showNavigation, setShowNavigation] = useState(true)
    const [view, setView] = useState<MediaView>('grid')
    const [showSettings,   setShowSettings]   = useState(false)
    const [prefs, updatePrefs] = usePrefs()

    const getSelection = (): ResourceSelection => {
      const urlParams = new URLSearchParams(location.search)

      const untagged = (urlParams.get("untagged") || "").toLowerCase() === "true"

      return {
        query: urlParams.get("q") || undefined,
        playlist: urlParams.get("playlist") || undefined,
        tag: untagged ? undefined : urlParams.get("tag") || undefined,
        untagged: untagged || undefined,
        streamable: urlParams.get("streamable")?.toLowerCase() === "false" ? false : undefined,
        sort: parseSortParam(urlParams.get("s") || "date_added;desc"),
        duration: parseThresholdRangeParam(urlParams.get("d") || "-"),
        uploadAge: parseThresholdRangeParam(urlParams.get("u") || "-"),
        resolution: parseResolutionParam(urlParams.get("vq") || "0"),
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
      updatePrefs({showSidebar: !prefs.showSidebar})
    }

    return (
        <>
          <ResourceViewModal resource= { showResource } onHide = { () => setShowResource(undefined) } />
          <Dialog.Root open={showSettings} onOpenChange={setShowSettings}>
            <Dialog.Portal>
              <Dialog.Overlay className="dialog-overlay" />
              <Dialog.Content className="dialog-content-window">
                <ConfigMenu />
              </Dialog.Content>
            </Dialog.Portal>
          </Dialog.Root>
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
                  cellWidth = { prefs.grid.cellWidth }
                  previewOptionsFn = { (v: ResourceDto) => {
                      return {
                        showPreviewOnHover: !isMobile,
                        showInfoBar: prefs.grid.showTitles,
                        showDates: prefs.grid.showDates,
                        showDuration: prefs.grid.showDuration,
                        showResolution: prefs.grid.showResolution,
                        aspectRatio: cssAspectRatio(
                          prefs.grid.aspectRatio,
                          prefs.grid.orientation
                        )
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
