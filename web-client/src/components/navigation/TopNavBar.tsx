import React, { useEffect, useState } from "react";
import { GoGrabber } from "react-icons/go";
import { MdTune } from "react-icons/md";
import { useHistory, useLocation } from "react-router-dom";
import { buildUrl, copyParams } from "../../api/Util";
import TagBar from "./TagBar";
import './TopNavBar.scss';

function TopNavBar(props: { onClickMenu: () => void, showTagsBar: boolean, onShowTagsBar: (show: boolean) => void }) {

  const location = useLocation();
  const history = useHistory();

  const [query, setQuery] = useState("")

  const doSearch = (e: any) => {
    e.preventDefault();
    const params = new URLSearchParams(location.search)
    const newParams = copyParams(params)
    newParams.set("q", query)
    history.push(buildUrl("/search", newParams));
  };

  useEffect(() => { setQuery(new URLSearchParams(location.search).get("q") || "") }, [location]);

  const queryChanged = (e: React.ChangeEvent<HTMLInputElement>) => {
    setQuery(e.target.value);
  };

  const clearQuery = () => {
    document.getElementById("nav-search-input")?.focus()
    setQuery("")
  }

  return(
    <div className="nav-bar-container">
      <div className="top-nav-bar">
          <GoGrabber className="nav-menu-button" onClick={props.onClickMenu} />
          <div key="nav-bar-left" className="nav-bar-spacer">
            
          </div>
          <div key="nav-bar-center" className="nav-bar-center">
            <form className="nav-search-form" onSubmit={doSearch} >
              <div key="nav-search-input" className="nav-search-input-container">
                <input placeholder="Search" className="nav-search-input" type="text" value={query} onChange={queryChanged} />
                <div 
                  className={ props.showTagsBar ? "toggle-tag-bar selected" : "toggle-tag-bar" }
                  onClick={() => { props.onShowTagsBar(!props.showTagsBar) }}>
                    <MdTune />
                </div>
              </div>
            </form>
          </div>
          <div key="nav-bar-right" className="nav-bar-spacer"></div>
      </div>
      { props.showTagsBar && <TagBar /> }
    </div>
  );
}

export default TopNavBar
