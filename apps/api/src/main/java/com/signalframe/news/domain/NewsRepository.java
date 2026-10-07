package com.signalframe.news.domain;

import com.signalframe.contract.NewsItem;
import java.util.*;

public interface NewsRepository {
  void save(NewsItem item);
  Optional<NewsItem> find(UUID id);
  List<NewsItem> recent();
}
