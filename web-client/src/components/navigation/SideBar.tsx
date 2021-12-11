import React, { useEffect, useState } from "react";
import { FaGithub, FaHome } from "react-icons/fa";
import { FiFolder, FiGrid, FiSettings, FiUser } from "react-icons/fi";
import { GoGrabber } from "react-icons/go";
import { Menu, MenuItem, ProSidebar, SidebarContent, SidebarFooter, SidebarHeader, SubMenu } from "react-pro-sidebar";
import { Api } from "../../api/Api";
import { Directory } from "../../api/Model";
import './SideBar.scss';
import Modal from "../shared/Modal";
import ConfigMenu from "./ConfigMenu";
import Login from "../Login";

const SideBar = (props: {collapsed: boolean, onHide: () => void }) => {

  const [playlists, setPlaylists] = useState<Array<Directory>>([]);
  const [showSettings, setShowSettings] = useState(false)

  useEffect(() => { Api.getPlaylists().then(response => { setPlaylists(response) }); }, [] );

  const [showLogin, setShowLogin] = useState(false)

  return (
    <>
    { <Modal visible = {showSettings} onHide={() => setShowSettings(false)}><ConfigMenu /></Modal>  }
    { <Modal visible = {showLogin} onHide={() => setShowLogin(false)}><Login onLogin={() => setShowLogin(false) }/></Modal>  }
    <ProSidebar className="my-sidebar" width={200} collapsedWidth={50} collapsed={props.collapsed}>
      <SidebarHeader className="sidebar-header">
        <GoGrabber className="sidebar-menu-icon" onClick={props.onHide} />
      </SidebarHeader>
      <SidebarContent>
        <Menu iconShape="circle">
          <MenuItem icon= { <FiUser /> } onClick = {() => setShowLogin(true)} >Profile</MenuItem>
          <MenuItem icon={ <FaHome />}><a href="/">Home</a></MenuItem>
          <SubMenu icon={ <FiFolder />} title="Directories" defaultOpen={true}>
          {
            playlists.map((d) =>  { return <MenuItem><a href={`/search?dir=${d.id}`}>{d.title}</a></MenuItem> }) 
          }
          </SubMenu>
          <MenuItem icon={ <FiGrid />}><a href="/grid">Grid</a></MenuItem>
          <MenuItem icon={<FiSettings />} onClick={() => setShowSettings(!showSettings)}>Settings</MenuItem>
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