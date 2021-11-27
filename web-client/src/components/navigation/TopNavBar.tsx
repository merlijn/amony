import React, { useEffect, useState } from "react";
import Form from "react-bootstrap/Form";
import FormControl from "react-bootstrap/FormControl";
import { GoGrabber } from "react-icons/go";
import { useHistory, useLocation } from "react-router-dom";
import { buildUrl } from "../../api/Util";
import ConfigMenu from "./ConfigMenu";
import './TopNavBar.scss';
import { FiHash } from "react-icons/fi";

function TopNavBar(props: { onClickMenu: () => void, showTagsBar: boolean, onShowTagsBar: (show: boolean) => void }) {

  const location = useLocation();
  const [query, setQuery] = useState("")
  const [showTagsBar, setShowTagsBar] = useState<Boolean>(props.showTagsBar)

  const history = useHistory();

  const doSearch = (e: any) => {
    e.preventDefault();
    const target = buildUrl("/search", new Map( [["q", query]] ))
    history.push(target);
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
          <div key="nav-bar-left" className="nav-bar-spacer">
            <GoGrabber className="nav-menu-button" onClick={props.onClickMenu} />
          </div>
          <div key="nav-bar-center" className="nav-bar-center">
            <Form className="nav-search-form" onSubmit={doSearch} inline>
              <div key="nav-search-input" className="nav-search-input-container">
                <FormControl className="nav-search-input" id="nav-search-input" size="sm" type="text" placeholder="Search" value={query} onChange={queryChanged} />
                
                <div className="tags-button" 
                  onClick={() => { setShowTagsBar(!showTagsBar); props.onShowTagsBar(!showTagsBar) }}>
                    <FiHash />
                </div>
                <div className="filter-button">
                  <ConfigMenu />
                </div>
              </div>
            </Form>
          </div>
          <div key="nav-bar-right" className="nav-bar-spacer"></div>
      </div>
      { showTagsBar && <TagBar /> }
      {/* {showFilterBar && <Filters />} */}
    </div>
  );
}

const TagBar = () => {

  const tags = ["nature", "people", "other", "ocean", "coastline", "car", "autumn"]

  return (
    <div className="tag-bar">
      <div className="tags">
        {
          tags.map(tag => <div className="tag">{tag}</div>)
        }
      </div>
    </div>);
}

const FilterBar = () => {

}

export default TopNavBar