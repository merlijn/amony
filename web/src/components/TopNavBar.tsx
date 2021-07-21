import React, {useEffect, useRef, useState} from "react";
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
import {doGET} from "../api/Api";
import {Tag} from "../api/Model";
import {DropdownButton} from "react-bootstrap";
import ConfigMenu from "./ConfigMenu";

function TopNavBar(props: { currentPage: number, lastPage: number }) {

  const location = useLocation();

  const [query, setQuery] = useState("")
  const [tags, setTags] = useState<Array<Tag>>([]);
  const [c, setC] = useState<Tag>({id: 0, title: ""})

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
      const found = tags.find((e) => e.id.toString() == cid)
      if (found)
        setC(found)
    }

    setQuery(params.get("q") || "")
  }, [location, tags]);

  // fetch tags
  useEffect(() => {
    doGET("/api/collections").then(response => { setTags(response) });
  }, [props]);

  const queryChanged = (e: React.ChangeEvent<HTMLInputElement>) => {
    setQuery(e.target.value);
  };

  // fixed="top"
  return(
    <Navbar className="TopNavBar" fixed="top">
        <div className="bar-left">
          <Nav.Link id="home-logo" href="/"><Image width="25px" height="25px" src="/templogo.png" />Amony</Nav.Link>
        </div>
        <div className="bar-center">
          <Form className="justify-content-center" onSubmit={doSearch} inline>
            <ConfigMenu />
            <FormControl id="search-input" className="mr-sm-1" size="sm" type="text" placeholder="Search" value={query} onChange={queryChanged} />
            <Button variant="outline-success" id="search-button" className="mr-sm-1" size="sm" onClick={doSearch}><Image width="25px" height="25px" src="/search_black_24dp.svg" /></Button>
            <DropdownButton title="#" size="sm">
              {
                tags.map((c) => {
                  return <NavDropdown.Item href={`/search?c=${c.id}`}>{c.title}</NavDropdown.Item>
                })
              }
            </DropdownButton>
          </Form>
        </div>
        <div className="bar-right">
          <Navbar.Text id="current-tag">{c.title}</Navbar.Text>
          <GalleryPagination className="absolute-right" current={props.currentPage} last={props.lastPage} />
        </div>
    </Navbar>
  );
}

export default TopNavBar