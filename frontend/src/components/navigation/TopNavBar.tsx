import React, {useContext, useEffect, useRef, useState} from "react";
import {isMobile} from "react-device-detect";
import {BsListUl} from "react-icons/bs";
import {GoSearch} from "react-icons/go";
import {IoGridOutline} from "react-icons/io5";
import {MdClose} from "react-icons/md";
import {useLocation, useNavigate} from "react-router-dom";
import {SessionContext} from "../../api/Constants";
import {MediaView} from "../../api/Model";
import {buildUrl, copyParams} from "../../api/Util";
import './TopNavBar.scss';
import {AiOutlineSetting} from "react-icons/ai";
import {CgProfile} from "react-icons/cg";
import Modal from "../common/Modal";
import Profile from "../dialogs/Profile";
import FileUpload from "../dialogs/FileUpload";
import {BiLogInCircle} from "react-icons/bi";
import {FiUpload} from "react-icons/fi";
import FilterDropDown from "./FilterDropdown";
import LoginDialog from "../dialogs/LoginDialog";
import { getOAuthProviders } from "../../api/generated";
import { OAuthProviderDto } from "../../api/generated/model";

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
  const [showUpload, setShowUpload] = useState(false)
  const [showLogin, setShowLogin] = useState(false)
  const [oauthProviders, setOauthProviders] = useState<OAuthProviderDto[] | null>(null)
  const session = useContext(SessionContext)

  const handleLoginClick = () => {
    // If we already fetched providers and there are multiple, show dialog
    if (oauthProviders && oauthProviders.length > 1) {
      setShowLogin(true);
      return;
    }
    
    // Otherwise fetch providers first
    getOAuthProviders()
      .then((data) => {
        setOauthProviders(data);
        if (data.length === 1) {
          // Single provider - redirect directly
          window.location.href = data[0].loginUrl;
        } else if (data.length > 1) {
          // Multiple providers - show dialog
          setShowLogin(true);
        }
      })
      .catch((err) => {
        console.error('Failed to load OAuth providers', err);
        // Show dialog anyway to display error
        setShowLogin(true);
      });
  };

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
    <Modal visible = { showUpload } onHide = { () => setShowUpload(false) }>
      <FileUpload />
    </Modal>
    <Modal visible = { showLogin } onHide = { () => setShowLogin(false) }>
      <LoginDialog onClose = { () => setShowLogin(false) } providers = { oauthProviders } />
    </Modal>
    <div className = "nav-bar-container">
      <div className = "top-nav-bar">
          <div key = "nav-bar-center" className = "nav-bar-center">
            <AiOutlineSetting className = "nav-menu-button" onClick = { props.onClickMenu } />
            <FiUpload className = "nav-menu-button" onClick = { () => setShowUpload(true) } />
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
            <BiLogInCircle className = "profile-button" onClick = { handleLoginClick } />
        }
      </div>
    </div>
    </>
  );
}

export default TopNavBar
