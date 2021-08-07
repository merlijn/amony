import React, {useEffect, useState} from 'react';
import {Api} from '../api/Api';
import {defaultPrefs, Prefs, SearchResult} from '../api/Model';
import Preview from './Preview';
import './Gallery.scss';
import {useLocation} from 'react-router-dom'
import {useCookiePrefs, useWindowSize} from "../api/Util";
import TopNavBar from "./TopNavBar";
import {pageSizes} from "../api/Constants";
import {useCookies, withCookies} from "react-cookie";

const gridSize = 350
const gridReRenderThreshold = 24

type Preferences = {
  gallery_columns?: number
  infinite_scroll: boolean
}

const Gallery = (props: { cols?: number}) => {

  const location = useLocation();
  const [searchResult, setSearchResult] = useState(new SearchResult(0, 0, 0,[]))

  const [prefs, setPrefs] = useCookiePrefs<Prefs>("prefs", "/", defaultPrefs)
  const urlParams = new URLSearchParams(location.search)
  const windowSize = useWindowSize(((oldSize, newSize) => Math.abs(newSize.width - oldSize.width) > gridReRenderThreshold));

  // grid size
  const [ncols, setNcols] = useState(3)
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

  useEffect(() => {

    const c = Math.min(Math.max(2, Math.round(windowSize.width / gridSize)), 5);
    if (c !== ncols)
      setNcols(c)

  },[windowSize]);

  const previews = searchResult.videos.map((vid, idx) => {

    let style: { } = { "--ncols" : `${ncols}` }

    if (idx % ncols === 0)
        style = { ...style, paddingLeft : "4px" }
    else if ((idx + 1) % ncols === 0)
        style = { ...style, paddingRight : "4px" }

    return <Preview style={style} className="grid-cell" key={`preview-${vid.id}`} vid={vid} showTitles={prefs.showTitles}/>
  })

  return (
    <div className="gallery-container full-width">
      <TopNavBar currentPage ={currentPage()} lastPage={Math.ceil(searchResult.total / pageSize)} />
      <div className="gallery">
        {previews}
      </div>
    </div>
  );
}

export default Gallery;
