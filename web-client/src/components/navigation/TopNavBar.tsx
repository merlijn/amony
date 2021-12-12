import _ from "lodash";
import React, { ReactNode, useEffect, useState } from "react";
import { GoGrabber } from "react-icons/go";
import { MdTune } from "react-icons/md";
import { useHistory, useLocation } from "react-router-dom";
import { Api } from "../../api/Api";
import { Constants } from "../../api/Constants";
import { Prefs } from "../../api/Model";
import { useCookiePrefs } from "../../api/ReactUtils";
import { buildUrl, copyParams } from "../../api/Util";
import { DropDown, MenuItem } from "../shared/DropDown";
import { MdSort, MdCropSquare } from "react-icons/md";
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

const TagBar = () => {

  const location = useLocation();
  const history = useHistory();

  const [selectedTag, setSelectedTag] = useState<string | undefined>(undefined)
  const [tags, setTags] = useState<Array<string>>([])

  const [prefs, updatePrefs] = useCookiePrefs<Prefs>("prefs/v1", "/", Constants.defaultPreferences)

  useEffect(() => {
    Api.getTags().then((updatedTags) => { setTags(updatedTags as Array<string>) })
  }, [])

  useEffect(() => {
    setSelectedTag(new URLSearchParams(location.search).get("tag") || undefined)
  }, [location]);

  const selectTag = (tag: string) => {
    const params = new URLSearchParams(location.search)
    const newParams = copyParams(params)

    if (tag === selectedTag)
      newParams.delete("tag")
    else
      newParams.set("tag", tag)

    history.push(buildUrl("/search", newParams ));
  };

  return (
    <div className="tag-bar">
      <div className="tags">

        <DropDownSelect
          toggle = { <MdSort /> }
          options = { Constants.sortOptions }
          selected = { prefs.sort }
          onSelect = { (v) => updatePrefs({...prefs, sort: v}) } />

        <DropDownSelect
          toggle = { <MdCropSquare /> }
          options = { Constants.resolutions }
          selected = { prefs.videoQuality }
          onSelect = { (v) => updatePrefs({...prefs, videoQuality: v}) } />
        {
          tags.map(tag => <div className={ tag === selectedTag ? "tag selected-tag" : "tag"} onClick = {() => selectTag(tag) }>{tag}</div>)
        }
      </div>

    </div>);
}

type SelectOption<T> = {
  value: T
  label: string
  icon?: ReactNode
}

const DropDownSelect = (props:{ toggle: ReactNode, options: Array<SelectOption<any>>, selected: any, onSelect: (v: any) => void }) => {
  return (
    <DropDown hideOnClick = {true} 
      toggleIcon = { props.toggle }
      toggleClassName = "custom-dropdown-toggle" 
      contentClassName = "dropdown-menu" 
      showArrow= { true }>
      {
        props.options.map((option) => {
          return <MenuItem 
                    className={_.isEqual(option.value,props.selected) ? "menu-item-selected" : ""}  
                    onClick={() => props.onSelect(option.value)}>
                    { option.icon } <div className="menu-label-no-wrap">{ option.label }</div>
                  </MenuItem>
        })
      }
  </DropDown>)
}


export default TopNavBar
