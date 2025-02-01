import _ from "lodash";
import React, {useContext, useEffect, useRef, useState} from "react";
import {isMobile} from "react-device-detect";
import {BsListUl} from "react-icons/bs";
import {GoSearch} from "react-icons/go";
import {IoGridOutline} from "react-icons/io5";
import {MdClose, MdTune} from "react-icons/md";
import {useLocation, useNavigate} from "react-router-dom";
import {Constants, durationAsParam, parseDurationParam, SessionContext, useSortParam} from "../../api/Constants";
import {MediaView} from "../../api/Model";
import {useUrlParam} from "../../api/ReactUtils";
import {buildUrl, copyParams} from "../../api/Util";
import {DropDown} from "../common/DropDown";
import './TopNavBar.scss';
import {AiOutlineSetting} from "react-icons/ai";
import {CgProfile} from "react-icons/cg";
import Modal from "../common/Modal";
import Profile from "../dialogs/Profile";
import {BiLogInCircle} from "react-icons/bi";


export type NavBarProps = {
  onClickMenu: () => void, 
  activeView: MediaView,
  onViewChange: (view: MediaView) => any
}

function TopNavBar(props: NavBarProps) {

  const location = useLocation();
  const navigate = useNavigate();
  const inputRef = useRef<HTMLInputElement>(null)
  const [query, setQuery] = useState("")
  const [showFilters, setShowFilters] = useState(false)
  const [showProfile, setShowProfile] = useState(false)
  const session = useContext(SessionContext)

  const doSearch = (e: any) => {
    e.preventDefault();
    const params = new URLSearchParams(location.search)
    const newParams = copyParams(params)
    newParams.set("q", query)
    navigate(buildUrl("/search", newParams));
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
    <>
    <Modal visible = { showProfile } onHide = { () => setShowProfile(false) }>
      <Profile onLogout = { () => { window.location.reload(); } } />
    </Modal>
    <div className = "nav-bar-container">
      <div className = "top-nav-bar">
          <div key = "nav-bar-center" className = "nav-bar-center">
            <AiOutlineSetting className = "nav-menu-button" onClick = { props.onClickMenu } />
            <form key="search-form" className = "nav-search-form" onSubmit = { doSearch } >
              <div className = "nav-search-input-container">
                <GoSearch className="search-icon" />
                <FilterDropDown onToggleFilter = { (v) => setShowFilters(v) } />
                <input 
                  ref         = { inputRef } 
                  style       = { showFilters ? { borderBottom: "none", borderBottomLeftRadius: 0, borderBottomRightRadius: 0, paddingBottom: 3 } : { } }
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
        {
          session.isLoggedIn() ?
            <CgProfile className = "profile-button" onClick = { () => setShowProfile(!showProfile) }/> :
            <a href="/login"><BiLogInCircle className = "profile-button" /></a>
        }
      </div>
    </div>
    </>
  );
}

const FilterDropDown = (props: { onToggleFilter: (v: boolean) => any}) => {

  const [vqParam, setVqParam]             = useUrlParam("vq", "0")
  const [sortParam, setSortParam]         = useSortParam()
  const [durationParam, setDurationParam] = useUrlParam("d", "-")
  const [uploadParam, setUploadParam]     = useUrlParam("u", "-")

  return( 
    <div className = "filter-dropdown-container">
      
      <DropDown 
        toggleIcon = { <MdTune className="filter-dropdown-icon" /> } 
        hideOnClick = { false } 
        onToggle = { props.onToggleFilter }
        contentClassName = "filter-dropdown-content">
        <div className = "filter-container">
          <RadioSelectGroup
            header        = "Sort"
            options       = { Constants.sortOptions }
            selectedValue = { sortParam }
            onChange      = { setSortParam }
          />
          <RadioSelectGroup
            header        = "Resolution"
            options       = { Constants.resolutions.map(option => ({ label: option.label, value: option.value.toString() })) }
            selectedValue = { vqParam }
            onChange      = { value => setVqParam(value) }
          />
          <RadioSelectGroup
            header        = "Duration"
            options       = { Constants.durationOptions }
            selectedValue = { parseDurationParam(durationParam) }
            onChange      = { value => setDurationParam(durationAsParam(value)) }
          />
          <RadioSelectGroup
            header        = "Upload date"
            options       = { Constants.uploadOptions }
            selectedValue = { parseDurationParam(durationParam)}
            onChange      = { value => setDurationParam(durationAsParam(value)) }
          />
        </div>
      </DropDown>
    </div>);
}

type RadioSelectProps<T> = {
  header: string;
  options: Array<{ label: string, value: T }>;
  selectedValue: T;
  onChange: (value: T) => void;
};

const RadioSelectGroup = <T,>({ header, options, selectedValue, onChange }: RadioSelectProps<T>) => {
  return (
    <div className="filter-section">
      <div className="section-header">{header}</div>
      {options.map((option, index) => (
        <div key={`${header}-${index}`} className="filter-option" onClick={() => onChange(option.value)}>
          <input
            type="radio"
            name={header}
            value={option.label}
            checked={_.isEqual(selectedValue, option.value)}
            onChange={() => onChange(option.value)}
          />
          {option.label}
        </div>
      ))}
    </div>
  );
};

export default TopNavBar
