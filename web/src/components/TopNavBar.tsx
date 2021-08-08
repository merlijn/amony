import React, {useEffect, useState} from "react";
import {useHistory, useLocation} from "react-router-dom";
import {buildUrl, copyParams} from "../api/Util";
import Navbar from "react-bootstrap/Navbar";
import Nav from "react-bootstrap/Nav";
import NavDropdown from "react-bootstrap/NavDropdown";
import Form from "react-bootstrap/Form";
import FormControl from "react-bootstrap/FormControl";
import Button from "react-bootstrap/Button";
import Image from 'react-bootstrap/Image';
import './TopNavBar.scss';
import GalleryPagination from "./GalleryPagination";
import {Api} from "../api/Api";
import {Tag} from "../api/Model";
import {DropdownButton} from "react-bootstrap";
import ConfigMenu from "./ConfigMenu";
import ImgWithAlt from "./shared/ImgWithAlt";

function TopNavBar(props: { currentPage: number, lastPage: number }) {

  const location = useLocation();

  const [query, setQuery] = useState("")
  const [tags, setTags] = useState<Array<Tag>>([]);
  const [selectedTag, setSelectedTag] = useState<Tag>({id: 0, title: ""})

  const history = useHistory();

  const doSearch = (e: any) => {
    e.preventDefault();
    const target = buildUrl("/search", new Map( [["q", query]] ))
    history.push(target);
  };

  useEffect(() => {
    const params = copyParams(new URLSearchParams(location.search))
    const cid = params.get("c")

    if (cid) {
      const found = tags.find((e) => e.id.toString() === cid)
      if (found)
        setSelectedTag(found)
    }

    setQuery(params.get("q") || "")
  }, [location, tags]);

  // fetch tags
  useEffect(() => {
    Api.getTags().then(response => { setTags(response) });
  }, [props]);

  const queryChanged = (e: React.ChangeEvent<HTMLInputElement>) => {
    setQuery(e.target.value);
  };

  const clearQuery = () => {
    console.log("setting focus")
    document.getElementById("nav-search-input")?.focus()
    setQuery("")
  }

  // fixed="top"
  return(
    <Navbar className="TopNavBar" fixed="top">
        <div key="nav-bar-left" className="bar-left">
          <Nav.Link id="home-logo" href="/"><ImgWithAlt width="25px" height="25px" src="/templogo.png" />Amony</Nav.Link>
        </div>
        <div key="nav-bar-center" className="bar-center">
          <Form className="justify-content-center" onSubmit={doSearch} inline>
            <ConfigMenu key="nav-config-menu"/>

            <div key="nav-search-input" className="nav-search-input mr-sm-1">
              <FormControl id="nav-search-input" size="sm" type="text" placeholder="Search" value={query} onChange={queryChanged} />
              <ImgWithAlt className="nav-clear-input action-icon-small" src="/clear_input.svg"
                          onClick={(e) => clearQuery() } />
            </div>

            <Button key="nav-search-button" variant="outline-success" id="search-button" className="mr-sm-1" size="sm" onClick={doSearch}><Image width="25px" height="25px" src="/search_black_24dp.svg" /></Button>
            <DropdownButton key="nav-tag-menu" title="#" size="sm">
              {
                tags.map((t) => {
                  return <NavDropdown.Item key={`nav-tag-${t.id}`} href={`/search?c=${t.id}`}>{t.title}</NavDropdown.Item>
                })
              }
            </DropdownButton>
          </Form>
        </div>
        <div key="nav-bar-right" className="bar-right">
          <Navbar.Text key="nav-current-tag" id="current-tag">{selectedTag.title}</Navbar.Text>
          <GalleryPagination key="nav-pagination" className="absolute-right" current={props.currentPage} last={props.lastPage} />
        </div>
    </Navbar>
  );
}

export default TopNavBar