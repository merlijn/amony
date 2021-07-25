import React, {useEffect, useState} from 'react';
import {Api} from '../api/Api';
import {SearchResult} from '../api/Model';
import Thumbnail from './Thumbnail';
import './Gallery.scss';
import {useLocation} from 'react-router-dom'
import {useWindowSize} from "../api/Util";
import TopNavBar from "./TopNavBar";
import {pageSizes} from "../api/Constants";

const gridSize = 350
const gridReRenderThreshold = 24

const Gallery = () => {

  const location = useLocation();
  const [searchResult, setSearchResult] = useState(new SearchResult(0, 0, 0,[]))
  const urlParams = new URLSearchParams(location.search)
  const size = useWindowSize(((oldSize, newSize) => Math.abs(newSize.width - oldSize.width) > gridReRenderThreshold));

  // grid size
  const ncols = Math.min(Math.max(2, Math.round(size.width / gridSize)), 5);
  const grid_class = `grid-${ncols}`

  const pageSize = pageSizes.get(ncols) || 24

  const currentPage = () => parseInt(urlParams.get("p") || "1");

  useEffect(() => {

      const offset = (currentPage()-1) * pageSize

      Api.getVideos(
        urlParams.get("q") || "",
        urlParams.get("c"),
        pageSize,
        offset).then(response => { setSearchResult(response); });

    }, [location]
  )

  const previews = searchResult.videos.map((vid) => {

    return <Thumbnail key={`preview-${vid.id}`} vid={vid} />
  })

  return (
    <div className="full-width">
      <TopNavBar currentPage={currentPage()} lastPage={Math.ceil(searchResult.total / pageSize)} />
      <div className="gallery">
        {previews}
      </div>
    </div>
  );
}

export default Gallery;
