import _ from "lodash";
import React, { ReactNode, useEffect, useState } from "react";
import { Dropdown, DropdownButton } from "react-bootstrap";
import Form from "react-bootstrap/Form";
import FormControl from "react-bootstrap/FormControl";
import { FiHash } from "react-icons/fi";
import { MdTune } from "react-icons/md";
import { GoGrabber } from "react-icons/go";
import { useHistory, useLocation } from "react-router-dom";
import { Constants } from "../../api/Constants";
import { Prefs } from "../../api/Model";
import { useCookiePrefs } from "../../api/ReactUtils";
import { buildUrl } from "../../api/Util";
import './TopNavBar.scss';
import { Api } from "../../api/Api";

function TopNavBar(props: { onClickMenu: () => void, showTagsBar: boolean, onShowTagsBar: (show: boolean) => void }) {

  const location = useLocation();
  const [query, setQuery] = useState("")

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
                
                <div className={ props.showTagsBar ? "tags-button selected" : "tags-button" }
                  onClick={() => { props.onShowTagsBar(!props.showTagsBar) }}>
                  <MdTune />
                </div>
              </div>
            </Form>
          </div>
          <div key="nav-bar-right" className="nav-bar-spacer"></div>
      </div>
      { props.showTagsBar && <TagBar /> }
    </div>
  );
}

const TagBar = () => {

  const [tags, setTags] = useState<Array<string>>([])

  const [prefs, updatePrefs] = useCookiePrefs<Prefs>("prefs", "/", Constants.defaultPreferences)

  useEffect(() => {
    Api.getTags().then((updatedTags) => { setTags(updatedTags as Array<string>) })
  }, [])

  console.log(`quality: ${prefs.videoQuality}`)

  return (
    <div className="tag-bar">
      <div className="tags">
      <DropDownSelect 
          title="Sort" 
          options={ Constants.sortOptions }
          selected={ prefs.sort }
          onSelect = { (v) => updatePrefs({...prefs, sort: v}) } />
        
        <DropDownSelect 
          title="Quality" 
          options={ Constants.resolutions }
          selected={ prefs.videoQuality }
          onSelect = { (v) => updatePrefs({...prefs, videoQuality: v}) } />
        {
          tags.map(tag => <div className="tag">{tag}</div>)
        }
      </div>
      
    </div>);
}

type SelectOption<T> = { 
  value: T
  label: string
  icon?: ReactNode
}

const DropDownSelect = (props:{ title: string, options: Array<SelectOption<any>>, selected: any, onSelect: (v: any) => void }) => {
  return (
    <DropdownButton size="sm" className="custom-dropdown" title={props.title}>
      {
        props.options.map((option) => {
          return <Dropdown.Item className={_.isEqual(option.value,props.selected) ? "selected" : ""} href="#" onClick={() => props.onSelect(option.value)}>{option.icon}{option.label}</Dropdown.Item>
        })
      }
  </DropdownButton>)
}


export default TopNavBar