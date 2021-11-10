import { useEffect, useState } from "react";
import { useLocation } from "react-router";
import { Video } from "../api/Model";
import { useListener } from "../api/Util";
import Gallery, { GalleryProps } from "../components/Gallery";
import TopNavBar from "../components/navbar/TopNavBar";
import VideoModal from "../components/shared/VideoModal";
import './Main.scss';

const Main = () => {

    const location = useLocation();
    
    const [galleryProps, setGalleryProps] = useState<{}>({})
    const [playVideo, setPlayVideo] = useState<Video | undefined>(undefined)
    const [showNavBar, setShowNavBar] = useState(true)
  
    useEffect(() => { 
      
      const urlParams = new URLSearchParams(location.search)  
      const q = urlParams.get("q")
      const d = urlParams.get("dir")
  
      setGalleryProps({
          query: q ? q : undefined, 
          directory: d ? d : undefined
        })
  
    }, [location])

    const keyDownHandler = (event: KeyboardEvent) => {
      if (event.code === 'Slash')
        setShowNavBar(!showNavBar)
    }
  
    useListener('keydown', keyDownHandler)
  
    return (
        <>
          { playVideo && <VideoModal video={playVideo} onHide={() => setPlayVideo(undefined) } />}
          <div className="gallery-container full-width">
            { showNavBar && <TopNavBar key="top-nav-bar" /> }
            <div style={ !showNavBar ?  { marginTop: 2 } : {} } key="gallery" className="gallery">
              <Gallery {...galleryProps} onClick = { (v: Video) => setPlayVideo(v) } />
            </div>
          </div>
        </>
    );
    
      
  }

  export default Main