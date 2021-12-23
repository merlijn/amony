import React, { useEffect, useRef, useState } from "react";
import { GoGrabber } from "react-icons/go";
import { FiSearch } from "react-icons/fi";
import { MdClose, MdTune } from "react-icons/md";

import { BsListUl } from "react-icons/bs";
import { IoGridOutline } from "react-icons/io5";

import { useHistory, useLocation } from "react-router-dom";
import { buildUrl, copyParams } from "../../api/Util";
import TagBar from "./TagBar";
import './TopNavBar.scss';
import { MediaView, Prefs } from "../../api/Model";
import { isMobile } from "react-device-detect";
import { DropDown } from "../common/DropDown";
import { useCookiePrefs, useStateNeq, useUrlParam } from "../../api/ReactUtils";
import { Constants } from "../../api/Constants";

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
          <div key = "nav-bar-left"   className = "nav-bar-spacer" />
          <div key = "nav-bar-center" className = "nav-bar-center">

            
            <form className="nav-search-form" onSubmit = { doSearch } >
              <div className="nav-search-input-container">
                <input ref = { inputRef } key="nav-search-input" placeholder="Search" className="nav-search-input" type="text" value={query} onChange={queryChanged} />
                <FilterDropDown />
                {/* { query !== "" && <MdClose onClick = { clearQuery } className = "nav-clear-input" /> } */}
                { props.playList && <div className = "playlist">{ props.playList }</div>}
              </div>
            </form>
            {
              !isMobile &&
                <div key="view-select" className="view-select-container">
                  <button 
                    className = { `button-grid-view ${(props.activeView === 'grid') && "view-selected"}`} 
                    onClick={() => props.onViewChange('grid')}><IoGridOutline />
                  </button>
                  <button 
                    className = { `button-list-view ${(props.activeView === 'list') && "view-selected"}`} 
                    onClick   = { () => props.onViewChange('list')}><BsListUl />
                  </button>
                </div>
            }
            </div> 
          <div key="nav-bar-right" className="nav-bar-spacer" />
      </div>
    </div>
  );
}

const FilterDropDown = () => {

  const [vqParam, setVqParam] = useUrlParam("vq")
  const vq = parseInt(vqParam || "0")

  return( 
    <div className = "filter-dropdown-container">
      <DropDown toggleIcon = { <MdTune /> } hideOnClick = { false } contentClassName="filter-dropdown-content">
        <div className="filter-container">
          <div key="filter-sort" className="filter-section">
            <div className="section-header">Sort</div>
            { Constants.sortOptions.map((option, index) => {
                return <div key={`sort-${index}`} className="filter-option">
                        <input type="radio" name="sort" value = { option.label } />
                        <label htmlFor = { option.label }>{option.label}</label>
                      </div>
              }) 
            }
          </div>
          <div key="filter-resolution" className="filter-section">
            <div className="section-header">Resolution</div>
            { Constants.resolutions.map((option, index) => {
                return <div key={`resolution-${index}`} className="filter-option">
                          <input 
                            type    = "radio" 
                            name    = "resolution" 
                            value   = { option.label } 
                            checked = { vq === option.value }
                            onChange = { (e) => setVqParam(option.value.toString()) }/>
                          <label htmlFor = { option.label }>{option.label}</label>
                      </div>
              }) 
            }
          </div>
          <div key="filter-duration" className="filter-section">
            <div className="section-header">Duration</div>
            { Constants.durationOptions.map((option, index) => {
                return <div key={`duration-${index}`} className="filter-option">
                        <input type="radio" name="duration" value = { option.label } />
                        <label htmlFor = { option.label }>{option.label}</label>
                      </div>
              }) 
            }
          </div>
        </div>
      </DropDown>
    </div>);
}

export default TopNavBar
