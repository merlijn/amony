import React, {useEffect, useState} from "react";
import {useHistory, useLocation} from "react-router-dom";
import {buildUrl, copyParams, useCookiePrefs} from "../api/Util";
import NavDropdown from "react-bootstrap/NavDropdown";
import Form from "react-bootstrap/Form";
import FormControl from "react-bootstrap/FormControl";
import './TopNavBar.scss';
import {Api} from "../api/Api";
import {defaultPrefs, Prefs, Tag} from "../api/Model";
import {ButtonGroup} from "react-bootstrap";
import ConfigMenu from "./ConfigMenu";
import ImgWithAlt from "./shared/ImgWithAlt";
import Button from "react-bootstrap/Button";
import Dropdown from "react-bootstrap/Dropdown";

function TopNavBar() {

  const location = useLocation();
  const [prefs, setPrefs] = useCookiePrefs<Prefs>("prefs", "/", defaultPrefs)
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
    const tagId = params.get("tag")

    if (tagId) {
      const found = tags.find((e) => e.id.toString() === tagId)
      if (found)
        setSelectedTag(found)
    }

    setQuery(params.get("q") || "")
  }, [location, tags]);

  // fetch tags
  useEffect(() => { Api.getTags().then(response => { setTags(response) }); }, []);

  const queryChanged = (e: React.ChangeEvent<HTMLInputElement>) => {
    setQuery(e.target.value);
  };

  const clearQuery = () => {
    document.getElementById("nav-search-input")?.focus()
    setQuery("")
  }

  // fixed="top"
  return(
    <div className="top-nav-bar">
        <div key="nav-bar-left" className="nav-bar-spacer"> </div>
        <div key="nav-bar-center" className="nav-bar-center">

          <Dropdown className="tag-list-dropdown" key="nav-tag-list" as={ButtonGroup} size="sm">
            <Button size="sm" className="home-button"><a href="/">
              <ImgWithAlt src="/home_black_24dp.svg" />
            </a></Button>

            <Dropdown.Toggle className="tag-list-toggle" split id="dropdown-split-basic" />

            <Dropdown.Menu>
              {
                tags.map((t) => {
                  return <NavDropdown.Item key={`nav-tag-${t.id}`} href={`/search?tag=${t.id}`}>{t.title}</NavDropdown.Item>
                })
              }
            </Dropdown.Menu>
          </Dropdown>

          <Form className="nav-search-form" onSubmit={doSearch} inline>
            <div key="nav-search-input" className="nav-search-input">
              <FormControl id="nav-search-input" size="sm" type="text" placeholder="Search" value={query} onChange={queryChanged} />
              <ImgWithAlt className="nav-clear-input action-icon-small" src="/clear_input.svg"
                          onClick={(e) => clearQuery() } />
            </div>
          </Form>

          <ConfigMenu key="nav-config-menu"/>
        </div>
        <div key="nav-bar-right" className="nav-bar-spacer"></div>
    </div>
  );
}

export default TopNavBar