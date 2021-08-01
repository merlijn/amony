import React, {useEffect, useState} from 'react';
import {Api} from '../api/Api';
import {SearchResult} from '../api/Model';
import Preview from './Preview';
import './Gallery.scss';
import {useLocation} from 'react-router-dom'
import {useWindowSize} from "../api/Util";
import TopNavBar from "./TopNavBar";
import {pageSizes} from "../api/Constants";

const gridSize = 350
const gridReRenderThreshold = 24

const Gallery = (props: { cols?: number}) => {

  const location = useLocation();
  const [searchResult, setSearchResult] = useState(new SearchResult(0, 0, 0,[]))
  const urlParams = new URLSearchParams(location.search)
  const size = useWindowSize(((oldSize, newSize) => Math.abs(newSize.width - oldSize.width) > gridReRenderThreshold));

  // grid size
  const ncols = 4 // Math.min(Math.max(2, Math.round(size.width / gridSize)), 5);

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

  const previews = searchResult.videos.map((vid, idx) => {

    let style = { }

    if (idx % ncols == 0)
        style = { paddingLeft : "4px" }
    else if ((idx + 1) % ncols == 0)
        style = { paddingRight : "4px" }

    return <Preview style = {style} className="grid-cell" key={`preview-${vid.id}`} vid={vid} />
  })

  return (
    <div className="gallery-container full-width">
      <TopNavBar currentPage={currentPage()} lastPage={Math.ceil(searchResult.total / pageSize)} />
      <div className="gallery">
        {previews}
      </div>
    </div>
  );
}

export default Gallery;
