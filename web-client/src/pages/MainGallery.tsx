import { useEffect, useState } from "react";
import { useLocation } from "react-router";
import { Video } from "../api/Model";
import Gallery, { GalleryProps } from "../components/Gallery";
import VideoModal from "../components/shared/VideoModal";

const MainGallery = () => {

    const location = useLocation();
    
    const [galleryProps, setGalleryProps] = useState<{}>({})
    const [playVideo, setPlayVideo] = useState<Video | undefined>(undefined)
  
    useEffect(() => { 
      
      const urlParams = new URLSearchParams(location.search)  
      const q = urlParams.get("q")
      const d = urlParams.get("dir")
  
      setGalleryProps({
          query: q ? q : undefined, 
          directory: d ? d : undefined
        })
  
    }, [location])
  
    return (
        <>
          { playVideo && <VideoModal video={playVideo} onHide={() => setPlayVideo(undefined) } />}
          <Gallery {...galleryProps} onClick = { (v: Video) => setPlayVideo(v) } />
        </>
    );
    
      
  }

  export default MainGallery