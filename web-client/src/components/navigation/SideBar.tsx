import React, { useState } from "react";
import { FaGithub } from "react-icons/fa";
import { FiFolder, FiGrid, FiSettings, FiUpload, FiUser } from "react-icons/fi";
import { GiAbstract020 } from "react-icons/gi";
import { IoCloseSharp } from "react-icons/io5"
import { useHistory } from "react-router-dom";
import { Api } from "../../api/Api";
import Modal from "../common/Modal";
import ConfigMenu from "../dialogs/ConfigMenu";
import FileUpload from "../dialogs/FileUpload";
import Login from "../session/Login";
import Profile from "../session/Profile";
import './SideBar.scss';

const SideBar = (props: {collapsed: boolean, onHide: () => void }) => {

  const [showSettings, setShowSettings] = useState(false)
  const [showLogin, setShowLogin] = useState(false)
  const [showProfile, setShowProfile] = useState(false)
  const [showFileUpload, setShowFileUpload] = useState(false)
  const history = useHistory();

  return (
    <>
    <Modal visible = { showSettings }   onHide = { () => setShowSettings(false) }>
      <ConfigMenu />
    </Modal>
    <Modal visible = { showLogin }      onHide = { () => setShowLogin(false) }>
      <Login onLoginSuccess={() => setShowLogin(false) }/>
    </Modal>
    <Modal visible = { showProfile }    onHide = { () => setShowProfile(false) }>
      <Profile onLogout={ () => setShowProfile(false) } />
    </Modal>
    <Modal visible = { showFileUpload } onHide = { () => setShowFileUpload(false) }>
      <FileUpload />
    </Modal>

    { /* invisible full screen element that hides the sidebar when clicked */ }
    <div className= "sidebar-hidden-modal" onClick = { props.onHide } />

    <div className = "sidebar">
      <div className="sidebar-header">
        <IoCloseSharp className = "close-sidebar-icon" onClick={props.onHide} />
      </div>
      <div className = "sidebar-menu">
        <MenuItem 
          icon    = { Api.session().isLoggedIn() ? <GiAbstract020 /> : <FiUser /> } 
          label   = { Api.session().isLoggedIn() ? "Profile" : "Log in" }
          onClick = { () => { Api.session().isLoggedIn() ? setShowProfile(true) : setShowLogin(true) } } 
        />
        { Api.session().isAdmin() && 
            <MenuItem 
              icon    = { <FiUpload /> } 
              label   = "Upload"
              onClick = { () => { setShowFileUpload(true) } } 
            />  
          }
        { (process.env.NODE_ENV === "development") && <MenuItem icon = { <FiFolder /> } label = "Playlists" /> }
        { (process.env.NODE_ENV === "development") && 
          <MenuItem icon = { <FiGrid /> } label = "Video wall" onClick = { () => history.push("/video-wall") }></MenuItem> 
        }
        <MenuItem 
          icon    = { <FiSettings /> } 
          label   = "Settings"
          onClick = { () => setShowSettings(!showSettings) }
        />
      </div>
      <div className="sidebar-footer">
        <a href="https://github.com/merlijn/amony"><FaGithub className="github-icon" /></a>
      </div>
    </div>
    </>
  );
}

const MenuItem = (props: { icon: React.ReactNode, label: string, onClick?: () => any}) => {

  return(
    <div className="menu-item" onClick = { () => props.onClick && props.onClick() }>
      <div className="menu-item-icon">{props.icon}</div>
      <div className="menu-item-label">{props.label}</div>
    </div>
  )
}

export default SideBar