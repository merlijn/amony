import _ from "lodash";
import React, { useEffect, useRef, useState } from "react";
import { isMobile } from "react-device-detect";
import { BsListUl } from "react-icons/bs";
import { GoGrabber, GoSearch } from "react-icons/go";
import { IoGridOutline } from "react-icons/io5";
import { MdClose, MdTune } from "react-icons/md";
import { useHistory, useLocation } from "react-router-dom";
import { Constants, durationAsParam, parseDurationParam, useSortParam } from "../../api/Constants";
import { MediaView } from "../../api/Model";
import { useUrlParam } from "../../api/ReactUtils";
import { buildUrl, copyParams } from "../../api/Util";
import { DropDown } from "../common/DropDown";
import './TopNavBar.scss';

export type NavBarProps = {
  onClickMenu: () => void, 
  activeView: MediaView,
  onViewChange: (view: MediaView) => any
}

function TopNavBar(props: NavBarProps) {

  const location = useLocation();
  const history = useHistory();
  const inputRef = useRef<HTMLInputElement>(null)
  const [query, setQuery] = useState("")
  const [showFilters, setShowFilters] = useState(false)

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
          <div key = "nav-bar-center" className = "nav-bar-center">
            <form key="search-form" className = "nav-search-form" onSubmit = { doSearch } >
              <div className = "nav-search-input-container">
                <GoSearch className="search-icon" />
                <FilterDropDown onToggleFilter = { (v) => setShowFilters(v) } />
                <input 
                  ref         = { inputRef } 
                  style       = { showFilters ? { borderBottom: "none", borderBottomLeftRadius: 0, borderBottomRightRadius: 0 } : { } }
                  key         = "nav-search-input" 
                  className   = "nav-search-input" 
                  placeholder = "Search" 
                  type        = "text" 
                  value       = { query } 
                  onChange    = { queryChanged } />
                { query !== "" && <MdClose onClick = { clearQuery } className = "nav-search-clear-input" /> }
              </div>
            </form>
            {
              !isMobile &&
                <div key="view-select" className="view-select-container">
                  <button 
                    className = { `button-grid-view ${(props.activeView === 'grid') && "view-selected"}`} 
                    onClick   = { () => props.onViewChange('grid') }><IoGridOutline />
                  </button>
                  <button 
                    className = { `button-list-view ${(props.activeView === 'list') && "view-selected"}`} 
                    onClick   = { () => props.onViewChange('list')}><BsListUl />
                  </button>
                </div>
            }
            </div> 
      </div>
    </div>
  );
}

const FilterDropDown = (props: { onToggleFilter: (v: boolean) => any}) => {

  const [vqParam, setVqParam] = useUrlParam("vq", "0")
  const [sortParam, setSortParam] = useSortParam()
  const [durationParam, setDurationParam] = useUrlParam("d", "-")

  return( 
    <div className = "filter-dropdown-container">
      
      <DropDown 
        toggleIcon = { <MdTune /> } 
        hideOnClick = { false } 
        onToggle = { props.onToggleFilter }
        contentClassName = "filter-dropdown-content">
        <div className = "filter-container">
          <div key="filter-sort" className="filter-section">
            <div className="section-header">Sort</div>
            { Constants.sortOptions.map((option, index) => {
                return <div key={`sort-${index}`} className="filter-option" onClick={() => setSortParam(option.value) }>
                         <input 
                           type     = "radio" 
                           name     = "sort" 
                           value    = { option.label } 
                           checked  = { _.isEqual(option.value, sortParam) }
                           onChange = { () => setSortParam(option.value) }/>
                         { option.label }
                      </div>
              }) 
            }
          </div>
          <div key = "filter-resolution" className = "filter-section">
            <div className = "section-header">Resolution</div>
            { Constants.resolutions.map((option, index) => {
                return <div key={`resolution-${index}`} className = "filter-option" onClick = { () => setVqParam(option.value.toString()) }>
                          <input 
                            type    = "radio" 
                            name    = "resolution" 
                            value   = { option.label } 
                            checked = { parseInt(vqParam) === option.value }
                            onChange = { () => setVqParam(option.value.toString()) }/>
                          {option.label}
                      </div>
              }) 
            }
          </div>
          <div key="filter-duration" className = "filter-section">
            <div className = "section-header">Duration</div>
            { Constants.durationOptions.map((option, index) => {
                return <div key={`duration-${index}`} className = "filter-option" onClick = { () => setDurationParam(durationAsParam(option.value))}>
                         <input 
                           type     = "radio" 
                           name     = "duration" 
                           checked  = { _.isEqual(option.value, parseDurationParam(durationParam)) }
                           onChange = { () => setDurationParam(durationAsParam(option.value)) }
                           value    = { option.label } />
                        {option.label}
                      </div>
              }) 
            }
          </div>
          <div key="filter-upload-date" className = "filter-section">
            <div className = "section-header">Upload date</div>
            { Constants.uploadOptions.map((option, index) => {
                return <div key={`duration-${index}`} className = "filter-option" onClick = { () => setDurationParam(durationAsParam(option.value))}>
                         <input 
                           type     = "radio" 
                           name     = "upload-date" 
                           checked  = { _.isEqual(option.value, parseDurationParam(durationParam)) }
                           onChange = { () => setDurationParam(durationAsParam(option.value)) }
                           value    = { option.label } />
                        {option.label}
                      </div>
              }) 
            }
          </div>
        </div>
      </DropDown>
    </div>);
}

export default TopNavBar
