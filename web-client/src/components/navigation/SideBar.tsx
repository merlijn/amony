import React, { useEffect, useState } from "react";
import { FaGithub, FaHome } from "react-icons/fa";
import { FiFolder, FiGrid, FiSettings } from "react-icons/fi";
import { GoGrabber } from "react-icons/go";
import { Menu, MenuItem, ProSidebar, SidebarContent, SidebarFooter, SidebarHeader, SubMenu } from "react-pro-sidebar";
import { Api } from "../../api/Api";
import { Directory } from "../../api/Model";
import './SideBar.scss';

const SideBar = (props: {collapsed: boolean, onHide: () => void }) => {

  const [dirs, setDirs] = useState<Array<Directory>>([]);

  useEffect(() => { Api.getDirectories().then(response => { setDirs(response) }); }, [] );

  return (
    <ProSidebar className="sidebar" width={200} collapsedWidth={50} collapsed={props.collapsed}>
      <SidebarHeader className="sidebar-header">
        <GoGrabber className="sidebar-menu-icon" onClick={props.onHide} />
      </SidebarHeader>
      <SidebarContent>
        <Menu iconShape="circle">
          <MenuItem icon={ <FaHome />}><a href="/">Home</a></MenuItem>
          <MenuItem icon={ <FiGrid />}><a href="/grid">Grid</a></MenuItem>
          <SubMenu icon={ <FiFolder />} title="Directories" defaultOpen={true}>
          {
            dirs.map((d) =>  { return <MenuItem><a href={`/search?dir=${d.id}`}>{d.title}</a></MenuItem> }) 
          }
          </SubMenu>
          <MenuItem icon={<FiSettings />}><a href="/">Settings</a></MenuItem>
        </Menu>
      </SidebarContent>
      <SidebarFooter className="sidebar-footer">
        <a href="https://github.com/merlijn/amony"><FaGithub className="github-icon" /></a>
      </SidebarFooter>
    </ProSidebar>
  );
}

export default SideBar