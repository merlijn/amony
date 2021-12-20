import React, { useEffect, useRef, useState } from "react";
import { GoGrabber } from "react-icons/go";
import { MdClose, MdTune } from "react-icons/md";

import { BsListUl } from "react-icons/bs";
import { IoGridOutline } from "react-icons/io5";

import { useHistory, useLocation } from "react-router-dom";
import { buildUrl, copyParams } from "../../api/Util";
import TagBar from "./TagBar";
import './TopNavBar.scss';
import { MediaView } from "../../api/Model";
import { isMobile } from "react-device-detect";

export type NavBarProps = {
  onClickMenu: () => void, 
  showTagsBar: boolean,
  activeView: MediaView,
  playList?: string,
  onViewChange: (view: MediaView) => any
}

function TopNavBar(props: NavBarProps) {

  const location = useLocation();
  const history = useHistory();
  const inputRef = useRef<HTMLInputElement>(null)

  const [query, setQuery] = useState("")

  const doSearch = (e: any) => {
    e.preventDefault();
    const params = new URLSearchParams(location.search)
    const newParams = copyParams(params)
    newParams.set("q", query)
    history.push(buildUrl("/search", newParams));
  };

  useEffect(() => { 
    setQuery(new URLSearchParams(location.search).get("q") || "") }, 
    [location]);

  const queryChanged = (e: React.ChangeEvent<HTMLInputElement>) => {
    setQuery(e.target.value);
  };

  const clearQuery = () => {
    // document.getElementById("nav-search-input")?.focus()
    inputRef?.current?.focus()
    setQuery("")
  }

  return(
    <div className = "nav-bar-container">
      <div className = "top-nav-bar">
          <GoGrabber className = "nav-menu-button" onClick = { props.onClickMenu } />
          <div key = "nav-bar-left" className = "nav-bar-spacer" />
          <div key="nav-bar-center" className="nav-bar-center">
            <form className="nav-search-form" onSubmit = { doSearch } >
              <div className="nav-search-input-container">
                <input ref = { inputRef } key="nav-search-input" placeholder="Search" className="nav-search-input" type="text" value={query} onChange={queryChanged} />
                { query !== "" && <MdClose onClick = { clearQuery } className = "nav-clear-input" /> }
                { props.playList && <div className = "playlist">{ props.playList }</div>}
              </div>
            </form>
            {
              !isMobile &&
                <div key="view-select" className="view-select-container">
                  <button 
                    className = { `button-list-view ${(props.activeView === 'list') && "view-selected"}`} 
                    onClick   = { () => props.onViewChange('list')}><BsListUl />
                  </button>
                  <button 
                    className = { `button-grid-view ${(props.activeView === 'grid') && "view-selected"}`} 
                    onClick={() => props.onViewChange('grid')}><IoGridOutline />
                  </button>
                </div>
            }
            </div> 
          <div key="nav-bar-right" className="nav-bar-spacer" />
      </div>
    </div>
  );
}

export default TopNavBar
