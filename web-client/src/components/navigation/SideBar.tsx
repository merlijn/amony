import React, { useState } from "react";
import { FaGithub } from "react-icons/fa";
import { FiFolder, FiGrid, FiSettings, FiUpload, FiUser } from "react-icons/fi";
import { GiAbstract020 } from "react-icons/gi";
import { GoGrabber } from "react-icons/go";
import { Menu, MenuItem, ProSidebar, SidebarContent, SidebarFooter, SidebarHeader } from "react-pro-sidebar";
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

    <ProSidebar className="my-sidebar" width={200} collapsedWidth={50} collapsed={props.collapsed}>
      <SidebarHeader className="sidebar-header">
        <GoGrabber className="sidebar-menu-icon" onClick={props.onHide} />
      </SidebarHeader>
      <SidebarContent>
        <Menu iconShape="circle">
          <MenuItem 
            icon = { Api.session().isLoggedIn() ? <GiAbstract020 /> : <FiUser /> } 
            onClick = { () => { Api.session().isLoggedIn() ? setShowProfile(true) : setShowLogin(true) } } >Profile
          </MenuItem>
          
          { Api.session().isAdmin() && 
              <MenuItem 
                icon = {<FiUpload /> } 
                onClick = { () => { setShowFileUpload(true) } }>Upload
              </MenuItem>
          }
          { (process.env.NODE_ENV === "development") && <MenuItem icon = { <FiFolder /> } title="Playlists" /> }
          { (process.env.NODE_ENV === "development") && <MenuItem icon = { <FiGrid /> }><a href="/video-wall">Grid</a></MenuItem> }
          <MenuItem icon = { <FiSettings />} onClick={() => setShowSettings(!showSettings)}>Settings</MenuItem>
        </Menu>
      </SidebarContent>
      <SidebarFooter className="sidebar-footer">
        <a href="https://github.com/merlijn/amony"><FaGithub className="github-icon" /></a>
      </SidebarFooter>
    </ProSidebar>
    </>
  );
}

export default SideBar