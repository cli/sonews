/*
 *   SONEWS News Server
 *   see AUTHORS for the list of contributors
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.sonews.feed;

import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.sonews.daemon.storage.Article;
import org.sonews.daemon.storage.Headers;
import org.sonews.util.Log;
import org.sonews.util.io.ArticleWriter;

/**
 * Pushes new articles to remote newsservers. This feeder sleeps until a new
 * message is posted to the sonews instance.
 * @author Christian Lins
 * @since sonews/0.5.0
 */
class PushFeeder extends AbstractFeeder
{
  
  private ConcurrentLinkedQueue<Article> articleQueue = 
    new ConcurrentLinkedQueue<Article>();
  
  @Override
  public void run()
  {
    while(isRunning())
    {
      try
      {
        synchronized(this)
        {
          this.wait();
        }
        
        Article  article = this.articleQueue.poll();
        String[] groups  = article.getHeader(Headers.NEWSGROUPS)[0].split(",");
        Log.msg("PushFeed: " + article.getMessageID(), true);
        for(Subscription sub : this.subscriptions)
        {
          // Circle check
          if(article.getHeader(Headers.PATH)[0].contains(sub.getHost()))
          {
            Log.msg(article.getMessageID() + " skipped for host " 
              + sub.getHost(), true);
            continue;
          }

          try
          {
            for(String group : groups)
            {
              if(sub.getGroup().equals(group))
              {
                // Delete headers that may cause problems
                article.removeHeader(Headers.NNTP_POSTING_DATE);
                article.removeHeader(Headers.NNTP_POSTING_HOST);
                article.removeHeader(Headers.X_COMPLAINTS_TO);
                article.removeHeader(Headers.X_TRACE);
                article.removeHeader(Headers.XREF);
                
                // POST the message to remote server
                ArticleWriter awriter = new ArticleWriter(sub.getHost(), sub.getPort());
                awriter.writeArticle(article);
                break;
              }
            }
          }
          catch(IOException ex)
          {
            Log.msg(ex, false);
          }
        }
      }
      catch(InterruptedException ex)
      {
        Log.msg("PushFeeder interrupted.", true);
      }
    }
  }
  
  public void queueForPush(Article article)
  {
    this.articleQueue.add(article);
    synchronized(this)
    {
      this.notifyAll();
    }
  }
  
}
